(ns ^:no-doc crux.tx.consumer
  (:require [clojure.tools.logging :as log]
            [crux.db :as db]
            [crux.node :as n]
            [crux.tx :as tx]
            [crux.tx.event :as txe]
            [crux.codec :as c])
  (:import java.io.Closeable
           java.time.Duration))

(defn- index-tx-log [{:keys [!error], ::n/keys [tx-log indexer document-store]} {::keys [^Duration poll-sleep-duration]}]
  (log/info "Started tx-consumer")
  (try
    (while true
      (let [consumed-txs? (when-let [tx-stream (try
                                                 (db/open-tx-log tx-log (::tx/tx-id (db/latest-completed-tx indexer)))
                                                 (catch InterruptedException e (throw e))
                                                 (catch Exception e
                                                   (log/warn e "Error reading TxLog, will retry")))]
                            (try
                              (let [tx-log-entries (iterator-seq tx-stream)
                                    consumed-txs? (not (empty? tx-log-entries))]
                                (doseq [tx-log-entry (partition-all 1000 tx-log-entries)]
                                  (doseq [doc-hashes (->> tx-log-entry
                                                          (mapcat ::txe/tx-events)
                                                          (mapcat tx/tx-event->doc-hashes)
                                                          (map c/new-id)
                                                          (partition-all 100))]
                                    (db/index-docs indexer (db/fetch-docs document-store doc-hashes))
                                    (when (Thread/interrupted)
                                      (throw (InterruptedException.))))

                                  (doseq [{:keys [::txe/tx-events] :as tx} tx-log-entry
                                          :let [tx (select-keys tx [::tx/tx-time ::tx/tx-id])]]
                                    (when-let [{:keys [tombstones]} (db/index-tx indexer tx tx-events)]
                                      (when (seq tombstones)
                                        (db/submit-docs document-store tombstones)))

                                    (when (Thread/interrupted)
                                      (throw (InterruptedException.)))))
                                consumed-txs?)
                              (finally
                                (.close ^Closeable tx-stream))))]
        (when (Thread/interrupted)
          (throw (InterruptedException.)))
        (when-not consumed-txs?
          (Thread/sleep (.toMillis poll-sleep-duration)))))

    (catch InterruptedException e)
    (catch Throwable e
      (reset! !error e)
      (log/error e "Error consuming transactions")))

  (log/info "Shut down tx-consumer"))

(defrecord TxConsumer [^Thread executor-thread, !error]
  db/TxConsumer
  (consumer-error [_] @!error)

  Closeable
  (close [_]
    (.interrupt executor-thread)
    (.join executor-thread)))

(def tx-consumer
  {:start-fn (fn [deps args]
               (let [!error (atom nil)]
                 (->TxConsumer (doto (Thread. #(index-tx-log (assoc deps :!error !error) args))
                                 (.setName "crux-tx-consumer")
                                 (.start))
                               !error)))
   :deps [::n/indexer ::n/document-store ::n/tx-log]
   :args {::poll-sleep-duration {:default (Duration/ofMillis 100)
                                 :doc "How long to sleep between polling for new transactions"
                                 :crux.config/type :crux.config/duration}}})
