(defproject g7s/skidder "0.1.0-SNAPSHOT"
  :description "Drag and drop for ClojureScript"
  :url "http://github.com/g7s/skidder"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/clojurescript "1.10.597" :scope "provided"]]

  :source-paths
  ["src"]

  :profiles
  {:dev  {:source-paths ["pages"]
          :dependencies [[com.google.javascript/closure-compiler-unshaded "v20191027"]
                         [org.clojure/google-closure-library "0.0-20191016-6ae1f72f"]
                         [thheller/shadow-cljs "2.8.99"]]}
   :repl {:source-paths ["dev"]
          :dependencies [[org.clojure/tools.namespace "1.0.0"]]
          :repl-options {:init-ns user
                         :nrepl-middleware
                         [shadow.cljs.devtools.server.nrepl04/middleware]}}})
