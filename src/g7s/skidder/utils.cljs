(ns g7s.skidder.utils
  (:require
   [goog.userAgent.product :as product]
   [goog.dom :as gdom]
   [clojure.string :as str]
   [g7s.skidder.protocols :as p])
  (:import
   (goog.math.interpolator Linear1)))


(defn ev-client-offset
  [e]
  [(.-clientX e) (.-clientY e)])


(defn node-client-offset
  [node]
  (when-some [node (if (== (.-nodeType node) 1) node (.-parentElement node))]
    (let [br (.getBoundingClientRect node)]
      [(.-left br) (.-top br)])))


(defn- img-node?
  [node]
  (and (identical? (.-nodeName node) "IMG")
       (or product/FIREFOX (gdom/isInDocument node))))


(defn dspn-offset
  [dsn dspn client-offset anchor offset]
  (let [is-img (img-node? dspn)
        ds-w   (.-offsetWidth dsn)
        ds-h   (.-offsetHeight dsn)
        dspn-w (if is-img (cond-> (.-width dspn)
                            product/SAFARI (/ (gdom/getPixelRatio))) ds-w)
        dspn-h (if is-img (cond-> (.-height dspn)
                            product/SAFARI (/ (gdom/getPixelRatio))) ds-h)
        odiff  (p/difference client-offset (node-client-offset (if is-img dsn dspn)))
        cofs-x (fn []
                 (let [odiff-x (p/coord-x odiff)
                       interp  (Linear1. #js [0 0.5 1]
                                         #js [odiff-x
                                              (* dspn-w (/ odiff-x ds-w))
                                              (- (+ odiff-x dspn-w) ds-w)])]
                   (.interpolate interp (p/coord-y anchor))))
        cofs-y (fn []
                 (let [odiff-y (p/coord-y odiff)
                       interp  (Linear1. #js [0 0.5 1]
                                         #js [odiff-y
                                              (* dspn-h (/ odiff-y ds-h))
                                              (- (+ odiff-y dspn-h) ds-h)])]
                   (cond-> (.interpolate interp (p/coord-y anchor))
                     (and is-img product/SAFARI) (+ (* (dec (gdom/getPixelRatio)) dspn-h)))))]
    [(if-some [ofs-x (p/coord-x offset)] ofs-x (cofs-x))
     (if-some [ofs-y (p/coord-y offset)] ofs-y (cofs-y))]))


(defn- get-data
  ([datat mtypes]
   (get-data datat mtypes ""))
  ([datat mtypes default]
   (or (first (filter #(not-empty (.getData datat %)) mtypes))
       default)))


(def native-types-conf
  {:native/file {:props {:files (fn [datat _] (.-files datat))
                         :items (fn [datat _] (.-items datat))}
                 :types #{"Files"}}
   :native/url  {:props {:urls #(str/split (get-data %1 %2) #"\n")}
                 :types #{"Url" "text/uri-list"}}
   :native/text {:props {:text get-data}
                 :types #{"Text" "text/plain"}}})


(def native-types
  (keys native-types-conf))


(defn find-native-type
  [datat]
  (when-some [datat-types (and datat (.-types datat))]
    (ffirst (filter (fn [[_ {:keys [types]}]]
                      (some types datat-types))
                    native-types-conf))))
