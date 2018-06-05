(ns crux.memdb
  (:require [crux.byte-utils :as bu]
            [crux.kv-store :as ks]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import java.io.Closeable))

(defn- atom-cursor->next! [cursor]
  (let [[k v :as kv] (first @cursor)]
    (swap! cursor rest)
    (when kv
      kv)))

(defn- persist-db [dir db]
  (let [file (io/file dir)]
    (.mkdirs file)
    (nippy/freeze-to-file (io/file file "memdb") @db)))

(defn- restore-db [dir]
  (->> (nippy/thaw-from-file (io/file dir "memdb"))
       (into (sorted-map-by bu/bytes-comparator))))

(defrecord MemKv [db db-dir persist-on-close?]
  ks/KvStore
  (open [this]
    (if (.isFile (io/file db-dir "memdb"))
      (assoc this :db (atom (restore-db db-dir)))
      (assoc this :db (atom (sorted-map-by bu/bytes-comparator)))))

  (new-snapshot [_]
    (let [db @db]
      (reify
        ks/KvSnapshot
        (new-iterator [_]
          (let [c (atom (seq db))]
            (reify
              ks/KvIterator
              (ks/-seek [this k]
                (reset! c (subseq db >= k))
                (atom-cursor->next! c))
              (ks/-next [this]
                (atom-cursor->next! c))
              Closeable
              (close [_]))))

        Closeable
        (close [_]))))

  (store [_ kvs]
    (swap! db merge (into {} kvs)))

  (delete [_ ks]
    (swap! db #(apply dissoc % ks)))

  (backup [_ dir]
    (let [file (io/file dir)]
      (when (.exists file)
        (throw (IllegalArgumentException. (str "Directory exists: " (.getAbsolutePath file)))))
      (persist-db dir db)))

  Closeable
  (close [_]
    (when (and db-dir persist-on-close?)
      (persist-db db-dir db))))
