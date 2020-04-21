(ns g7s.skidder
  (:require
   [g7s.skidder.protocols :as p]
   [g7s.skidder.impl :as impl]
   [g7s.skidder.impl.connector.html5 :refer [new-html5-connector]]))


(def ^:private initial-dnd-state
  {:status       :status/selecting
   :offsets      nil
   :drag-data    nil
   :drop-data    nil
   :selected-ids []
   :dragging-ids []
   :dropping-ids []})


(def ^:private dnd-state (atom initial-dnd-state))


(def ^:private system-state (atom nil))


(defn on-system-change
  [{:keys [start stop] :or {start (fn []) stop (fn [])}}]
  (let [key (gensym "dnd")]
    (add-watch system-state key
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (if (seq new-state)
                     (start)
                     (stop)))))
    (fn []
      (remove-watch system-state key))))


(defn started?
  []
  (seq @system-state))


(defn connector
  []
  (first @system-state))


(defn state-info
  []
  (second @system-state))


(defn start!
  ([]
   (start! :html5))
  ([connector-type]
   (start! connector-type {}))
  ([connector-type opts]
   (when-not (started?)
     (let [si        (impl/new-dnd-info dnd-state)
           sa        (impl/new-dnd-actions dnd-state si)
           connector (case connector-type
                       :html5 (new-html5-connector si sa (:connector opts {})))]
       (p/setup connector)
       (reset! system-state [connector si sa])))))


(defn stop!
  []
  (when (started?)
    (p/teardown (connector))
    (reset! system-state nil)))
