(ns g7s.skidder.protocols)


(defprotocol Cartesian
  "Cartesian coordinate system"
  (coord-x [p])
  (coord-y [p])
  (difference [p1 p2]))


(defprotocol DragSource
  (ds-type [this])
  (can-drag? [this si ds-id] "Return whether this drag source can be dragged.")
  (on-begin-drag [this si ds-id] "Called when the drag begins. The returned value will be available at the drop result.")
  (on-end-drag [this si ds-id]) "Called when the drag ends.")


(defprotocol NativeDragSource
  (read-drag-data [this datat]))


(defprotocol DropTarget
  (accepts [this] "Return a set of drag source types that this drop target accepts.")
  (can-drop? [this si ds-id dt-id] "Return whether a drop can happen on this drop target.")
  (on-hover [this si dt-id] "Called when a hover happens on this drop target.")
  (on-leave [this si dt-id] "Called when a leave happens on this drop target.")
  (on-drop [this si ds-id dt-id] "Called when a drop happens on this drop target."))


(defprotocol DragSourceRegistry
  (add-ds! [this ds] "Add the drag source into registry and return its unique id.")
  (get-ds [this ds-id] [this ds-id options])
  (remove-ds! [this ds-id]))


(defprotocol DropTargetRegistry
  (add-dt! [this dt] "Add the drop target into registry and return its unique id.")
  (get-dt [this dt-id] [this dt-id options])
  (remove-dt! [this dt-id]))


(defprotocol StateInfo
  (ds-reg [this] "Return a DragSourceRegistry.")
  (dt-reg [this] "Return a DropTargetRegistry.")
  (selected-ids [this] "A sequence of drag source ids that are selected for dragging.")
  (selecting? [this] "Return whether the selection process is in progress.")
  (dragging-ids [this] "A sequence of drag source ids that are currently dragging.")
  (dragging? [this] [this ds-id] "Return whether the dragging is in progress.")
  (drag-data [this] [this ds-id] "Return a map of `ds-id` => `on-begin-drag` returned value.")
  (over? [this dt-id] [this dt-id options] "Return whether the drag is over a drop target.")
  (hovering? [this] "Return whether drag is currently hovering over a drop target.")
  (dropping-ids [this] "A sequence of drop target ids that the drag is currently over.")
  (dropping? [this] "Return whether a drop action is in progress.")
  (dropped? [this] "Return whether a drop action has completed.")
  (drop-data [this] [this ds-id] "Return a map of `ds-id` => `on-drop` returned value.")
  (offset [this offset-type] "The offset coords for an offset type."))


(defprotocol StateActions
  (select-ds! [this ds-id] "Add a drag source to the drag selection.")
  (deselect-ds! [this ds-id] "Remove a drag source from the drag selection.")
  (set-drag-data! [this data] [this ds-id data] "Set the drag data for a drag source.")
  (begin-drag! [this options])
  (publish-drag! [this options])
  (end-drag! [this options])
  (hover! [this dt-hover-ids options])
  (leave! [this dt-id options])
  (drop! [this options]))


(defprotocol StateWatcher
  (watch-change [this watcher-fn] [this watcher-fn options])
  (watch-offset [this watcher-fn]))


(defprotocol Connector
  (setup [this])
  (teardown [this])
  (connect-ds [this id node] [this id node options])
  (connect-dp [this id node] [this id node options])
  (connect-dt [this id node] [this id node options]))
