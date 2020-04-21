(ns g7s.skidder.impl
  (:require
   [clojure.set :as set]
   [g7s.skidder.utils :as utils]
   [g7s.skidder.protocols :as p]))


(deftype NativeDragSource [ntype conf ^:mutable __data]
  p/NativeDragSource
  (read-drag-data [this datat]
    (if (seq __data)
      __data
      (let [data (into {} (for [[prop prop-fn] (:props conf)]
                            [prop (prop-fn datat (:types conf))]))]
        (set! __data data)
        data)))
  p/DragSource
  (can-drag? [this si ds-id] true)
  (ds-type [this] ntype)
  (on-begin-drag [this si ds-id]
    __data)
  (on-end-drag [this si ds-id]))


(defn new-nds
  [ntype]
  (NativeDragSource. ntype (get utils/native-types-conf ntype) {}))


(defn is-nds?
  [o]
  (instance? NativeDragSource o))


(extend-type cljs.core/PersistentVector
  p/Cartesian
  (coord-x [v] (first v))
  (coord-y [v] (peek v))
  (difference [v1 v2]
    [(- (p/coord-x v1) (p/coord-x v2))
     (- (p/coord-y v1) (p/coord-y v1))]))


(deftype DragSourceRegistry [__reg]
  p/DragSourceRegistry
  (add-ds! [this ds]
    (let [ds-id (str (gensym "DS__"))]
      (vswap! __reg assoc ds-id ds)
      ds-id))
  (get-ds [this ds-id]
    (get @__reg ds-id))
  (remove-ds! [this ds-id]
    (vswap! __reg dissoc ds-id)
    true))


(deftype DropTargetRegistry [__reg]
  p/DropTargetRegistry
  (add-dt! [this dt]
    (let [dt-id (str (gensym "DT__"))]
      (vswap! __reg assoc dt-id dt)
      dt-id))
  (get-dt [this dt-id]
    (get @__reg dt-id))
  (remove-dt! [this dt-id]
    (vswap! __reg dissoc dt-id)
    true))


(defn new-ds-registry
  []
  (DragSourceRegistry. (volatile! {})))


(defn new-dt-registry
  []
  (DropTargetRegistry. (volatile! {})))


(defn dragging-ds-types
  [si]
  (map #(p/ds-type (p/get-ds (p/ds-reg si) %)) (p/dragging-ids si)))


(defn set-offset!
  [state offset-type offset]
  (swap! state assoc-in [:offsets offset-type] offset)
  true)


(deftype DnDInfo [state __ds-reg __dt-reg]
  p/StateInfo
  (ds-reg [this] __ds-reg)
  (dt-reg [this] __dt-reg)
  (selected-ids [this] (get @state :selected-ids))
  (selecting? [this] (= :status/selecting (get @state :status)))
  (dragging-ids [this] (get @state :dragging-ids))
  (dragging? [this] (= :status/dragging (get @state :status)))
  (dragging? [this ds-id]
    (contains? (into #{} (p/dragging-ids this)) ds-id))
  (drag-data [this]
    (get @state :drag-data))
  (drag-data [this ds-id]
    (get-in @state [:drag-data ds-id]))
  (over? [this dt-id]
    (p/over? this dt-id {:shallow false}))
  (over? [this dt-id {:keys [shallow]}]
    (if (or (nil? dt-id)
            (empty? (p/dragging-ids this)))
      false
      (let [dt    (p/get-dt (p/dt-reg this) dt-id)
            ddsts (dragging-ds-types this)]
        (if (and (seq ddsts)
                 (not (some (p/accepts dt) ddsts)))
          false
          (if shallow
            (= dt-id (first (p/dropping-ids this)))
            (some #{dt-id} (p/dropping-ids this)))))))
  (hovering? [this] (= :status/hovering (get @state :status)))
  (dropping-ids [this] (get @state :dropping-ids))
  (dropping? [this] (= :status/dropping (get @state :status)))
  (dropped? [this] (= :status/dropped (get @state :status)))
  (drop-data [this]
    (get @state :drop-data))
  (drop-data [this ds-id]
    (get-in @state [:drop-data ds-id])))


(deftype DnDActions [state si]
  p/StateActions
  (select-ds! [this ds-id]
    (swap! state update :selected-ids conj ds-id)
    true)
  (deselect-ds! [this ds-id]
    (swap! state update :selected-ids remove #{ds-id})
    true)
  (set-drag-data! [this data]
    (swap! state assoc :drag-data data)
    true)
  (set-drag-data! [this ds-id data]
    (swap! state assoc-in [:drag-data ds-id] data)
    true)
  (begin-drag! [this {:keys [init-ds-id ; The drag source that initiated the drag
                             init-pointer-offset
                             get-ds-offset]}]
    (assert (not (p/dragging? si)) "Cannot call begin-drag! while dragging.")
    (assert (every? #(p/get-ds (p/ds-reg si) %) (p/selected-ids si)) "Drag source ids should be registered.")
    (set-offset! state :init-pointer-offset init-pointer-offset)
    (let [draggable-ids (into [] (filter #(p/can-drag? (p/get-ds (p/ds-reg si) %) si %) (get @state :selected-ids)))]
      ;; Now the selected ids become the ones that can be actually dragged
      (swap! state assoc :selected-ids draggable-ids)
      (if (empty? draggable-ids)
        (do (set-offset! state :pointer-offset nil)
            false
            #_(set-offset! state :ds-offset nil))
        (do (set-offset! state :pointer-offset init-pointer-offset)
            (doseq [draggable-id draggable-ids]
              (let [init-ds-offset
                    (when init-pointer-offset
                      (assert (fn? get-ds-offset) "Option get-ds-offset must be a function.")
                      (get-ds-offset draggable-id))
                    ds (p/get-ds (p/ds-reg si) draggable-id)]
                #_(dispatch :init-coords {:pointer-offset init-pointer-offset
                                          :ds-offset      init-ds-offset})
                #_(p/pin ds-registry draggable-id)
                #_(dispatch :begin-drag {:item           item
                                         :item-type      (pds/type ds)
                                         :ds-id          draggable-id
                                         :pointer-offset init-pointer-offset
                                         :ds-offset      init-ds-offset
                                         :public-ds?     (boolean publish-ds)})
                (swap! state assoc-in
                       [:drag-data draggable-id] (p/on-begin-drag ds si draggable-id))))
            true))))
  (publish-drag! [this _]
    (swap! state merge {:status       :status/dragging
                        :dragging-ids (p/selected-ids si)
                        :selected-ids []}))
  (end-drag! [this _]
    (assert (or (p/dragging? si) (p/dropped? si)) "Should be dragging or finished dropping to call end-drag.")
    (doseq [dragging-id (p/dragging-ids si)
            :let        [ds (p/get-ds (p/ds-reg si) dragging-id)]
            :when       (some? ds)]
      (p/on-end-drag ds si dragging-id))
    (swap! state merge {:offsets      nil
                        :drag-data    nil
                        :drop-data    nil
                        :status       :status/selecting
                        :dragging-ids []
                        :dropping-ids []}))
  (drop! [this _]
    (assert (p/dragging? si) "Should be dragging to call drop!.")
    (swap! state assoc :status :status/dropping)
    (doseq [dragging-id (p/dragging-ids si)
            dropping-id (p/dropping-ids si)
            :let        [dt (p/get-dt (p/dt-reg si) dropping-id)]
            :when       (and (some? dt) (p/can-drop? dt si dragging-id dropping-id))]
      (swap! state assoc-in [:drop-data dragging-id] (p/on-drop dt si dragging-id dropping-id)))
    (swap! state assoc :status :status/dropped))
  (hover! [this dt-hover-ids {:keys [pointer-offset]}]
    (assert (p/dragging? si) "Should be dragging to call hover!.")
    (assert (not (or (p/dropping? si) (p/dropped? si))) "Cannot call hover after drop.")
    (swap! state assoc :status :status/hovering)
    (let [ddsts (into #{} (dragging-ds-types si))
          dropping-ids
          (into [] (for [dt-id dt-hover-ids
                         :let  [dt (p/get-dt (p/dt-reg si) dt-id)]
                         :when (and (some? dt)
                                    (seq (set/intersection ddsts (p/accepts dt))))]
                     dt-id))]
      (swap! state assoc :dropping-ids dropping-ids)
      (doseq [dt-id dropping-ids
              :let  [dt (p/get-dt (p/dt-reg si) dt-id)]
              :when (some? dt)]
        (p/on-hover dt si dt-id))
      (swap! state assoc :status :status/dragging)
      (set-offset! state :pointer-offset pointer-offset)))
  (leave! [this dt-leave-ids {:keys [pointer-offset]}]
    (assert (p/dragging? si) "Should be dragging to call leave!.")
    (swap! state assoc :status :status/leaving)
    (let [ddsts (into #{} (dragging-ds-types si))]
      (doseq [dt-id dt-leave-ids
              :let  [dt (p/get-dt (p/dt-reg si) dt-id)]
              :when (and (some? dt)
                         (seq (set/intersection ddsts (p/accepts dt))))]
        (p/on-leave dt si dt-id))
      (swap! state assoc :status :status/dragging)
      (set-offset! state :pointer-offset pointer-offset))))


(deftype DnDWatcher [state]
  p/StateWatcher
  (watch-change [this watcher-fn]
    (p/watch-change this watcher-fn {}))
  (watch-change [this watcher-fn options]
    (let [wkey (str (random-uuid))]
      (add-watch state
                 wkey
                 (fn [_key _ref old-state new-state]
                   (when (not= (:id old-state) (:id new-state))
                     (watcher-fn old-state new-state))))
      (fn [] (remove-watch state wkey))))
  (watch-offset [this watcher-fn]
    (let [wkey (str (random-uuid))]
      (add-watch state
                 wkey
                 (fn [_key _ref old-state new-state]
                   (when (not= (:offsets old-state) (:offsets new-state))
                     (watcher-fn (:offsets old-state) (:offsets new-state)))))
      (fn [] (remove-watch state wkey)))))


(defn new-dnd-info
  [state]
  (DnDInfo. state (new-ds-registry) (new-dt-registry)))


(defn new-dnd-actions
  [state si]
  (DnDActions. state si))
