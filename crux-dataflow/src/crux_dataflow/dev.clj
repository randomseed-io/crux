(ns crux-dataflow.dev
  (:require
    [crux.api :as api]
    [crux-dataflow.api-2 :as dataflow]
    [clojure.pprint :as pp]
    [crux-dataflow.schema :as schema])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (java.io Closeable)
           (java.time Duration)))

(declare node)

(defn- submit-sync [txes]
  (let [tx-data (api/submit-tx node txes)]
    (api/sync node (Duration/ofSeconds 20))
    tx-data))

(def task-schema
  (schema/inflate
    {:task/owner [:Eid]
     :task/title [:String]
     :task/content [:String]
     :task/followers [:Eid ::schema/set]}))

(def user-schema
  (schema/inflate
    {:user/name [:String]
     :user/email [:String]
     :user/knows [:Eid ::schema/set]
     :user/likes [:String ::schema/list]}))

(defonce node
  (api/start-node
    {:crux.node/topology :crux.standalone/topology
     :crux.node/kv-store :crux.kv.rocksdb/kv
     :crux.standalone/event-log-kv-store :crux.kv.rocksdb/kv
     :crux.standalone/event-log-dir "data/eventlog"
     :crux.kv/db-dir "data/db-dir"}))

(def ^Closeable crux-3df
  (do
    (if (bound? #'crux-3df)
      (.close crux-3df))
    (dataflow/start-dataflow-tx-listener
      node
      {:crux.dataflow/schemas {:user user-schema, :task task-schema}
       :crux.dataflow/debug-connection? true
       :crux.dataflow/embed-server?     false})))

(def ^LinkedBlockingQueue sub1
  (dataflow/subscribe-query! crux-3df
    {:crux.dataflow/sub-id ::one
     :crux.dataflow/query-name "user-email"
     :crux.df/results-shape :crux.df.results-shape/tuples
     :crux.dataflow/query
     {:find ['?name '?email]
      :where
      [['?user :user/name '?name]
       ['?user :user/email '?email]]}}))

(def ^LinkedBlockingQueue sub2
  (dataflow/subscribe-query! crux-3df
    {:crux.dataflow/sub-id ::one
     :crux.dataflow/query-name "user-todos"
     :crux.dataflow/results-shape :crux.dataflow.results-shape/maps
     :crux.dataflow/query
     {:find ['?name '?email '?user-todo]
      :where
      [['?user :user/name '?name]
       ['?user :user/email '?email]
       ['?task :task/owner '?user]
       ['?task :task/title '?user-todo]]}}))

(submit-sync
  [[:crux.tx/put
    {:crux.db/id :ids/patrik
     :user/name  "Pat"
     :user/email "pat@pat.pat"}]
   [:crux.tx/put
    {:crux.db/id :ids.tasks/one
     :task/owner :ids/patrik
     :task/title "Groceries"}]])

(submit-sync
  [[:crux.tx/put
    {:crux.db/id :ids.tasks/one
     :task/owner :ids/patrik
     :task/title "Gceries"}]])

(.poll sub1 10 TimeUnit/MILLISECONDS)
(.poll sub2 10 TimeUnit/MILLISECONDS)



(assert
  (= '{:find [?name ?email ?user-todo],
       :where [[?user :user/name ?name]
               [?user :user/email ?email]
               [?task :task/owner "#crux/id :ids/patrik"]
               [?task :task/title ?user-todo]],
       :rules nil}
     (schema/prepare-query
       (merge user-schema task-schema)
       {:find ['?name '?email '?user-todo]
        :where
              [['?user :user/name '?name]
               ['?user :user/email '?email]
               ['?task :task/owner  :ids/patrik]
               ['?task :task/title '?user-todo]]})))


(comment
  (pp/pprint crux-3df)

  (api/submit-tx node
     [[:crux.tx/put
       {:crux.db/id :patrik
        :user/name  "Patrik"
        ; :user/knows [:ids/bart] ; fixme may not index properly
        ; :user/likes ["apples" "daples"] ; fixme fails to accept seqs
        :user/email "eiowefojhhhh"}]])

  (pp/pprint @(:query-listeners (.-conn crux-3df)))

  (.close node)
  (.close crux-3df))