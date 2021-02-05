# skidder

ClojureScript drag and drop library.


## Documentation

`skidder` is a system that you can start, stop and listen for changes

```clojure

(ns test.app
  (:require
   [skidder.core :as skidder]))


(skidder/start! :html5)

```

When skidder starts it adds some (required) event listeners. Those listeners will be
removed when the system stops

```clojure

(skidder/stop!)

```

skidder is built around [protocols](src/skidder/protocols.cljs). The most important
protocols are `DragSource`, `DropTarget`, `StateInfo` and `Connector`.


### Drag Source

A drag source is anything that can be dragged (usually a DOM node). Implementors of
the `DragSource` protocol will have to implement the following functions

```clojure
(ds-type [this] "Return the type of this drag source.")
(can-drag? [this state-info ds-id] "Return whether this drag source can be dragged.")
(on-begin-drag [this state-info ds-id] "Called when the drag begins. The returned value will be available at the drop result.")
(on-end-drag [this state-info ds-id] "Called when the drag ends.")
```

NOTES:

1. `ds-type` - Will be checked against a drop target's `accepts` set
1. `can-drag?` - If this function returns `false` then the element will not be dragged.
1. `on-begin-drag` - Because its return value will be available when its dropped on a target, you
    should return drag source data that you might need to execute some action on drop.


### Drop Target

A drop target is anything that can accept a drag source element (usually a DOM node). Implementors
of the `DropTarget` protocol will have to implement the following functions

```clojure
(exclusive? [this] "Return whether this drop target should be treated as an exclusive one.")
(accepts [this] "Return a set of drag source types that this drop target accepts.")
(can-drop? [this state-info ds-id dt-id] "Return whether a drop can happen on this drop target.")
(on-hover [this state-info dt-id] "Called when a hover happens on this drop target.")
(on-enter [this state-info dt-id] "Called when an enter happens on this drop target.")
(on-leave [this state-info dt-id] "Called when a leave happens on this drop target.")
(on-drop [this state-info ds-id dt-id] "Called when a drop happens on this drop target.")
```

NOTES:

1. `exclusive` - If an exclusive drop target is contained inside another drop target then the drop will
happen only on this drop target
1. `on-hover` - This will be called very often when above a target so don't do anything expensive in there


### State Information

When a skidder system starts it holds information about all kinds of things like which
drag sources are registered, which drop targets are registered, is a dragging operation in progress?,
is it hovering over some drop target? etc. To see what queries are available for the state information
you can refer to the `StateInfo` protocol. One can get the state information from skidder
(assuming that the system has started)

```clojure
(skidder/state-info)
```

### Connector

The connector is used to connect a drag source or a drop target to a DOM element. One can access the
connector of a started system from skidder

```clojure
(skidder/connector)
```

skidder provides a `:html5` connector that makes use of the HTML5 Drag and Drop API.


## Example (React)

Suppose that we have a React application and we want to incorporate the skidder library.
First we need a way to create and register a drag source and a drop target:

```clojure
(ns react.example
  (:require
   [skidder.protocols :as p]
   [skidder.core :as skidder]))

(defn map->ds
  "Given a map return a reified drag source."
  [{:keys [type can-drag? begin-drag end-drag]}]
  (reify
    p/DragSource
    (ds-type [this] type)
    (can-drag? [this si ds-id]
      (if can-drag?
        (can-drag? si ds-id)
        true))
    (on-begin-drag [this si ds-id]
      (when begin-drag
        (begin-drag si ds-id)))
    (on-end-drag [this si ds-id]
      (when end-drag
        (end-drag si ds-id)))))

(defn register-ds
  "Register a drag source and return its \"unique\" id."
  [ds]
  (let [ds-id (str (gensym "DS__"))]
    (p/add-ds! (p/ds-reg (skidder/state-info)) ds ds-id)
    ds-id))


(defn map->dt
  "Given a map return a reified drop target."
  [{:keys [accepts exclusive can-drop? hover enter leave drop]}]
  (reify
    p/DropTarget
    (exclusive? [this] exclusive)
    (accepts [this] accepts)
    (can-drop? [this si ds-id dt-id]
      (if can-drop?
        (can-drop? si ds-id dt-id)
        true))
    (on-hover [this si dt-id]
      (when hover
        (hover si dt-id)))
    (on-enter [this si dt-id]
      (when enter
        (enter si dt-id)))
    (on-leave [this si dt-id]
      (when leave
        (leave si dt-id)))
    (on-drop [this si ds-id dt-id]
      (when drop
        (drop si ds-id dt-id)))))

(defn register-dt
  "Register a drop target and return its \"unique\" id."
  [dt]
  (let [dt-id (str (gensym "DT__"))]
    (p/add-dt! (p/dt-reg (skidder/state-info)) dt dt-id)
    dt-id))
```

Next we need a way to connect a drag source / drop target to a DOM element. This can be
accomplished for example with the use of a React Hook that will use the connector and return
a React ref for the DOM element that we want to connect:

```clojure
(defn use-ds
  "React hook for a drag source."
  [spec]
  (let [ds-ref     (js/React.useRef nil)
        dispose-fn (js/React.useRef nil)]
    (js/React.useEffect
     (fn []
       (let [start  (fn []
                      (when-some [node (.-current ds-ref)]
                        (if (skidder/started?)
                          (let [ds-id   (register-ds (map->ds spec))
                                disconn (p/connect-ds (skidder/connector) ds-id node)]
                            (set! (.-current dispose-fn)
                                  (fn []
                                    (let [si (skidder/state-info)]
                                      (when-not (p/dragging? si ds-id)
                                        (p/remove-ds! (p/ds-reg si) ds-id)))
                                    (disconn))))
                          (set! (.-current dispose-fn) nil))))
             stop   (fn []
                      (when-let [dispose (.-current dispose-fn)]
                        (dispose)))
             remove (skidder/on-system-change {:start start
                                               :stop  stop})]
         (start)
         (fn []
           (stop)
           (remove))))
     #js [spec])
    ds-ref))


(defn use-dt
  "React hook for a drop target."
  [spec]
  (let [dt-ref     (js/React.useRef nil)
        dispose-fn (js/React.useRef nil)]
    (js/React.useEffect
     (fn []
       (let [start  (fn []
                      (when-some [node (.-current dt-ref)]
                        (if (skidder/started?)
                          (let [dt-id   (register-dt (map->dt spec))
                                disconn (p/connect-dt (skidder/connector) dt-id node)]
                            (set! (.-current dispose-fn)
                                  (fn []
                                    (disconn)
                                    (p/remove-dt! (p/dt-reg (skidder/state-info)) dt-id))))
                          (set! (.-current dispose-fn) nil))))
             stop   (fn []
                      (when-let [dispose (.-current dispose-fn)]
                        (dispose)))
             remove (skidder/on-system-change {:start start
                                               :stop  stop})]
         (start)
         (fn []
           (stop)
           (remove))))
     #js [spec])
    dt-ref))
```

Then it is trivial to add drag and drop functionality to your React components

```clojure
(rum/defc cheese
  [weight]
  (let [cheese-ref (use-ds {:type       :cheese
                            :begin-drag (constantly weight)})]
    [:.cheese {:ref cheese-ref}
     "🧀"]))


(rum/defc mouse
  []
  (let [mouse-ref (use-dt {:accepts #{:cheese}
                           :enter   (fn []
                                      (js/console.log "Gimme! Gimme!"))
                           :leave   (fn []
                                      (js/console.log "HEY! Right here!"))
                           :drop    (fn [state-info ds-id _]
                                      (let [weight (p/drag-data state-info ds-id)]
                                        (js/console.log (str "Yum Yum! " weight " grams of cheese!"))))})]
    [:.mouse {:ref mouse-ref}
     "🐭"]))
```


## License

Copyright © 2020 Gerasimos

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
