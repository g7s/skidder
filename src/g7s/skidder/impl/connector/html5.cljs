(ns g7s.skidder.impl.connector.html5
  (:require
   [goog.userAgent.product :as product]
   [clojure.set :as set]
   [clojure.string :as str]
   [g7s.skidder.utils :as utils]
   [g7s.skidder.protocols :as p]
   [g7s.skidder.impl :refer [new-nds is-nds?]]))


(defprotocol EnterLeaveTracker
  (enter-node [this node] "Return whether the node is the first to enter.")
  (leave-node [this node] "Return whether the node is the last to leave.")
  (reset-all [this]))


(deftype HTML5EnterLeaveTracker [enters node-in-doc?]
  EnterLeaveTracker
  (enter-node [this node]
    ;; When we enter a `node` the `enters` consist of
    ;; the ancestry of `node` plus the `node` itself
    (let [prev-len   (count @enters)
          new-enters (set/union (into #{} (filter #(and (node-in-doc? %)
                                                        (or (not (.-contains %))
                                                            (.contains % node)))
                                                  @enters))
                                #{node})]
      (vreset! enters new-enters)
      (and (= prev-len 0) (> (count new-enters) 0))))
  (leave-node [this node]
    (let [prev-len   (count @enters)
          new-enters (set/difference (into #{} (filter node-in-doc? @enters))
                                     #{node})]
      (vreset! enters new-enters)
      (and (> prev-len 0) (= (count new-enters) 0))))
  (reset-all [this] (vreset! enters #{})))


(defn new-tracker
  [node-in-doc?]
  (HTML5EnterLeaveTracker. (volatile! #{}) node-in-doc?))


(defn- set-drag-image?
  [datat]
  (and (some? datat)
       (fn? (.-setDragImage datat))))


(def ^:private dspn-default-opts
  {:anchor-x 0.5
   :anchor-y 0.5
   :capture  false})


(let [handlers (volatile! {})]
  (defn- defhandler
    ([capture handler-kw handler]
     (let [ev-name (peek (str/split (name handler-kw) #"-"))]
       (defhandler capture handler-kw ev-name handler)))
    ([capture handler-kw ev-name handler]
     (let [handler-kw (if capture
                        (keyword (str (name handler-kw) "-capture"))
                        handler-kw)]
       (vswap! handlers assoc handler-kw {:event   (name ev-name)
                                          :capture capture
                                          :fn      handler}))))

  (def defbubble (partial defhandler false))

  (def defcapture (partial defhandler true))

  (defn create-listeners
    [connector]
    (into {} (for [[kw v] @handlers]
               [kw (update v :fn #(partial % connector))]))))


(defn add-event-listener
  [node {:keys [event fn capture]}]
  (.addEventListener node event fn capture))


(defn add-mlisteners
  [node mlisteners]
  (doseq [[_ listener] mlisteners]
    (add-event-listener node listener)))


(defn remove-event-listener
  [node {:keys [event fn capture]}]
  (.removeEventListener node event fn capture))


(defn remove-mlisteners
  [node mlisteners]
  (doseq [[_ listener] mlisteners]
    (remove-event-listener node listener)))


(defprotocol PHTML5Connector
  (alt-pressed? [this])
  (is-native-drag? [this])
  (begin-native-drag! [this ntype])
  (end-native-drag! [this])
  (init-current-drag! [this ds-id])
  (clear-current-drag! [this]))


(defn- reset-hids!
  [connector]
  (vreset! (.-hids connector) #{}))


(defn- reset-lids!
  [connector]
  (vreset! (.-lids connector) #{}))


(defn- set-drop-effect!
  [datat ef]
  (when (some? datat)
    (set! (.-dropEffect datat) ef)))


(defn- get-drop-effect
  [connector]
  ;; MUL: When we implement multiple selection this has to change
  (let [si    (.-si connector)
        ds-id (first (p/dragging-ids si))]
    (if (is-nds? (p/get-ds (p/ds-reg si) ds-id))
      "copy"
      (or (:drop-effect (second (get @(.-mdsn connector) ds-id)))
          (if (alt-pressed? connector) "copy" "move")))))


(defn- node-in-doc?
  [connector node]
  (if-let [doc (:document (.-options connector))]
    (and (.-body doc) (.contains (.-body doc) node))
    false))


(defn- can-drop-here?
  "Return whether at least one of the dragging ids can be dropped to at least one of the `hids`."
  [connector hids]
  (let [si (.-si connector)]
    (some (fn [[ds-id dt-id]]
            (some-> (p/get-dt (p/dt-reg si) dt-id)
                    (p/can-drop? si ds-id dt-id)))
          (for [dt-id hids
                ds-id (p/dragging-ids si)]
            [ds-id dt-id]))))


(defn- end-drag-when-removed-from-dom
  [connector]
  (let [ds-id (.-__ds-id connector)
        [dsn] (get @(.-mdsn connector) ds-id)]
    (when-not (node-in-doc? connector dsn)
      (when (clear-current-drag! connector)
        (p/end-drag! (.-sa connector) {})
        (p/remove-ds! (p/ds-reg (.-si connector)) ds-id)))))


(defcapture :top-dragstart
  (fn [connector _]
    (clear-current-drag! connector)))


;;; Here we listen for a draggable node to fire the dragstart event
;;; and we add this to the selection (in the future when we need
;;; proper selection of multiple drag sources this will have to change)
;;; We also stop propagation in order to prevent the selection of parent
;;; drag sources in a nested scenario
;;; MUL: When we implement multiple selection this has to change
(defbubble :dragstart
  (fn [connector ds-id]
    (fn [e]
      (when (and (not (.-defaultPrevented e))
                 (empty? (p/selected-ids (.-si connector))))
        (p/select-ds! (.-sa connector) ds-id)))))


(defbubble :top-dragstart
  (fn [connector e]
    (when-not (.-defaultPrevented e)
      (let [si    (.-si connector)
            sa    (.-sa connector)
            datat (.-dataTransfer e)]
        (when (p/dragging? si)
          (p/end-drag! sa {}))
        (if-let [ntype (and (is-native-drag? connector)
                            (utils/find-native-type datat))]
          (begin-native-drag! connector ntype)
          (if (p/selecting? si)
            (let [client-offset (utils/ev-client-offset e)
                  get-ds-offset (comp utils/node-client-offset first @(.-mdsn connector))]
              (if (p/begin-drag! sa {:init-pointer-offset client-offset
                                     :get-ds-offset       get-ds-offset})
                ;; Again here we assume that a selection consists only of one ds-id
                ;; MUL: When we implement multiple selection this has to change
                (let [ds-id (first (p/selected-ids si))
                      [dsn] (get @(.-mdsn connector) ds-id)
                      [dspn dspn-opts]
                      (get @(.-mdspn connector) ds-id [dsn])
                      {:keys [anchor-x anchor-y offset-x offset-y capture]}
                      (merge dspn-default-opts dspn-opts)]
                  (when (and (some? dspn) (set-drag-image? datat))
                    (let [dspn-offset (utils/dspn-offset dsn
                                                         dspn
                                                         client-offset
                                                         [anchor-x anchor-y]
                                                         [offset-x offset-y])]
                      (.setDragImage datat dspn (p/coord-x dspn-offset) (p/coord-y dspn-offset))))
                  (try
                    (.setData datat "application/json" #js {})
                    (catch js/Error e))
                  (init-current-drag! connector ds-id)
                  (if capture
                    (js/setTimeout #(p/publish-drag! sa {}) 0)
                    (p/publish-drag! sa {})))
                (.preventDefault e)))
            (when-not (and (not (.-types datat))
                           (or (not (.-hasAttribute (.-target e)))
                               (.hasAttribute (.-target e) "draggable")))
              (.preventDefault e))))))))


(defcapture :top-dragend
  (fn [connector e]
    (when (clear-current-drag! connector)
      (p/end-drag! (.-sa connector) {}))))


(defcapture :top-dragenter
  (fn [connector e]
    (reset-hids! connector)
    (let [si           (.-si connector)
          first-enter? (enter-node (.-tracker connector) (.-target e))]
      (when (and first-enter? (not (p/dragging? si)))
        (when-let [ntype (and (is-native-drag? connector)
                              (utils/find-native-type (.-dataTransfer e)))]
          ;; A native drag came in from outside of the window
          (begin-native-drag! connector ntype))))))


(defbubble :dragenter
  (fn [connector dt-id]
    (fn [e]
      (vswap! (.-hids connector) conj dt-id))))


(defbubble :top-dragenter
  (fn [connector e]
    (let [si    (.-si connector)
          sa    (.-sa connector)
          hids  @(.-hids connector)
          datat (.-dataTransfer e)]
      (reset-hids! connector)
      (when (p/dragging? si)
        (set! (.-__alt connector) (.-altKey e))
        (when-not product/FIREFOX
          (p/hover! sa hids {:pointer-offset (utils/ev-client-offset e)}))
        (when (can-drop-here? connector hids)
          (.preventDefault e)
          (set-drop-effect! datat (get-drop-effect connector)))))))


(defcapture :top-dragover
  (fn [connector e]
    (reset-hids! connector)))


(defbubble :dragover
  (fn [connector dt-id]
    (fn [e]
      (vswap! (.-hids connector) conj dt-id))))


(defbubble :top-dragover
  (fn [connector e]
    (let [si    (.-si connector)
          sa    (.-sa connector)
          hids  @(.-hids connector)
          datat (.-dataTransfer e)]
      (reset-hids! connector)
      (.preventDefault e)
      (if-not (p/dragging? si)
        (set-drop-effect! datat "none")
        (do
          (set! (.-__alt connector) (.-altKey e))
          (p/hover! sa hids {:pointer-offset (utils/ev-client-offset e)})
          (if (can-drop-here? connector hids)
            (set-drop-effect! datat (get-drop-effect connector))
            (set-drop-effect! datat "none")))))))


(defcapture :top-dragleave
  (fn [connector e]
    (reset-lids! connector)
    (let [datat (.-dataTransfer e)
          ntype (and (is-native-drag? connector)
                     (utils/find-native-type datat))]
      (when ntype
        (.preventDefault e))
      (when (and (leave-node (.-tracker connector) (.-target e))
                 ntype)
        (end-native-drag! connector)))))


(defbubble :dragleave
  (fn [connector dt-id]
    (fn [e]
      (vswap! (.-lids connector) conj dt-id))))


(defbubble :top-dragleave
  (fn [connector e]
    (let [lids @(.-lids connector)]
      (reset-lids! connector)
      (.preventDefault e)
      (p/leave! (.-sa connector) lids {:pointer-offset (utils/ev-client-offset e)}))))


(defcapture :top-drop
  (fn [connector e]
    (.preventDefault e)
    (when (is-native-drag? connector)
      (let [si     (.-si connector)
            nds-id (first (p/dragging-ids si))
            nds    (p/get-ds (p/ds-reg si) nds-id)]
        (p/set-drag-data! (.-sa connector) nds-id (p/read-drag-data nds (.-dataTransfer e)))))
    (reset-all (.-tracker connector))))


(defbubble :top-drop
  (fn [connector e]
    (let [si (.-si connector)
          sa (.-sa connector)]
      (p/hover! sa (p/dropping-ids si) {})
      (p/drop! sa {})
      (if (is-native-drag? connector)
        (end-native-drag! connector)
        (end-drag-when-removed-from-dom connector)))))


(defcapture :end-drag-when-removed-from-dom :mousemove
  (fn [connector e]
    (end-drag-when-removed-from-dom connector)))


(def ^:private window-listeners
  [:top-dragstart-capture
   :top-dragstart
   :top-dragend-capture
   :top-dragenter-capture
   :top-dragenter
   :top-dragover-capture
   :top-dragover
   :top-dragleave-capture
   :top-dragleave
   :top-drop-capture
   :top-drop])


(def ^:private MM-TIMEOUT 1000)


;;; Notes:
;;; dsn is the Drag Source node
;;; dspn is the Drag Source preview node
;;; hids refer to the Drop Target ids that are set when hovering
;;; mdsn a map of ds-id to [dsn opts]
;;; mdspn is a map of ds-id to [dspn opts]
;;; __ds-id the current dragging ds-id

(deftype HTML5Connector [si sa tracker options listeners hids lids mdsn mdspn
                         ^:mutable __alt
                         ^:mutable __ds-id
                         ^:mutable __mm-timer]
  PHTML5Connector
  (alt-pressed? [this]
    (boolean __alt))
  (is-native-drag? [this]
    (and (some? __ds-id)
         (is-nds? (p/get-ds (p/ds-reg si) __ds-id))))
  (begin-native-drag! [this ntype]
    (let [nds-id (p/add-ds! (p/ds-reg si) (new-nds ntype))]
      (p/select-ds! sa nds-id)
      (p/begin-drag! sa {})
      (init-current-drag! this nds-id)
      (p/publish-drag! sa {})))
  (end-native-drag! [this]
    (p/end-drag! sa {})
    (p/remove-ds! (p/ds-reg si) __ds-id)
    (clear-current-drag! this))
  (init-current-drag! [this ds-id]
    (clear-current-drag! this)
    (set! __ds-id ds-id)
    (set! __mm-timer
          (js/setTimeout
           (fn []
             (when-some [window (:window options)]
               (add-event-listener window (:end-drag-when-removed-from-dom-capture @listeners))))
           MM-TIMEOUT))
    true)
  (clear-current-drag! [this]
    (or (when __ds-id
          (set! __ds-id nil)
          (when-some [window (:window options)]
            (js/clearTimeout __mm-timer)
            (remove-event-listener window (:end-drag-when-removed-from-dom-capture @listeners)))
          (set! __mm-timer nil)
          (reset-hids! this)
          (reset-lids! this)
          true)
        false))
  p/Connector
  (setup [this]
    (when-let [window (:window options)]
      (vreset! listeners (create-listeners this))
      (add-mlisteners window (select-keys @listeners window-listeners))))
  (teardown [this]
    (when-let [window (:window options)]
      (remove-mlisteners window (select-keys @listeners window-listeners))
      (vreset! listeners nil)
      (clear-current-drag! this)))
  (connect-ds [this id node]
    (p/connect-ds this id node {}))
  (connect-ds [this id node options]
    (vswap! mdsn assoc id [node options])
    (.setAttribute node "draggable" "true")
    (let [dragstart-listener (update (:dragstart @listeners) :fn (fn [f] (f id)))]
      (add-event-listener node dragstart-listener)
      (fn []
        (vswap! mdsn dissoc id)
        (.setAttribute node "draggable" "false")
        (remove-event-listener node dragstart-listener))))
  (connect-dp [this id node]
    (p/connect-dp this id node {}))
  (connect-dp [this id node options]
    (vswap! mdspn assoc id [node options])
    (fn []
      (vswap! mdspn dissoc id)))
  (connect-dt [this id node]
    (p/connect-dt this id node {}))
  (connect-dt [this id node options]
    (let [dragenter-listener (update (:dragenter @listeners) :fn (fn [f] (f id)))
          dragover-listener  (update (:dragover @listeners) :fn (fn [f] (f id)))
          dragleave-listener (update (:dragleave @listeners) :fn (fn [f] (f id)))]
      (add-event-listener node dragenter-listener)
      (add-event-listener node dragover-listener)
      (add-event-listener node dragleave-listener)
      (fn []
        (remove-event-listener node dragenter-listener)
        (remove-event-listener node dragover-listener)
        (remove-event-listener node dragleave-listener)))))


(defn new-html5-connector
  ([si sa]
   (new-html5-connector si sa {}))
  ([si sa opts]
   (let [connector (HTML5Connector. si
                                    sa
                                    nil
                                    (merge {:window   js/window
                                            :document js/document}
                                           opts)
                                    (volatile! nil)
                                    (volatile! nil)
                                    (volatile! nil)
                                    (volatile! nil)
                                    (volatile! nil)
                                    nil
                                    nil
                                    nil)
         tracker   (new-tracker (partial node-in-doc? connector))]
     (set! (.-tracker connector) tracker)
     connector)))
