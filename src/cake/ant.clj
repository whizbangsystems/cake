(ns cake.ant
  "Lancet-inspired ant helpers."
  (:use [clojure.useful :only [conj-vec]])
  (:import [org.apache.tools.ant Project NoBannerLogger]
           [org.apache.tools.ant.types Path FileSet Environment$Variable]
           [java.beans Introspector]))

(def ant-project (atom nil))

(defmulti  coerce (fn [type val] [type (class val)]))
(defmethod coerce [java.io.File String] [_ str] (java.io.File. str))
(defmethod coerce :default [type val]
  (if (= String type)
    (str val)
    (try (cast type val)
         (catch ClassCastException e
           val))))

(defn- property-key [property]
  (keyword (.. (re-matcher #"\B([A-Z])" (.getName property))
               (replaceAll "-$1")
               toLowerCase)))

(defn set-attributes! [instance attrs]
  (doseq [property (.getPropertyDescriptors (Introspector/getBeanInfo (class instance)))]
    (let [key    (property-key property)
          val    (attrs key)
          setter (.getWriteMethod property)]
      (when-not (or (nil? val) (nil? setter))
        (let [type (first (.getParameterTypes setter))]
          (.invoke setter instance (into-array [(coerce type val)])))))))

(defn make*
  ([class attrs]
     (doto (make* class)
       (set-attributes! attrs)))
  ([class]
     (let [signature (into-array Class [Project])]
       (try (.newInstance (.getConstructor class signature)
              (into-array [@ant-project]))
            (catch NoSuchMethodException e
              (let [instance (.newInstance class)]
                (try (.invoke (.getMethod class "setProject" signature)
                       instance (into-array [@ant-project]))
                     (catch NoSuchMethodException e))
                instance))))))

(defmacro make [task attrs & forms]
  `(doto (make* ~task ~attrs)
     ~@forms))

(defmacro ant [task attrs & forms]
  `(doto (make* ~task ~attrs)
     ~@forms
     (.execute)))

(defn get-reference [ref-id]
  (.getReference @ant-project ref-id))

(defmacro add-fileset [task type attrs & forms]
  `(.addFileset ~task
     (make ~type ~attrs ~@forms)))

(defn path [& paths]
  (let [path (Path. @ant-project)]
    (doseq [p paths]
      (if (.endsWith p "*")
        (add-fileset path FileSet {:includes "*.jar" :dir (subs p 0 (dec (count p)))})
        (.. path createPathElement (setPath p))))
    path))

(defn classpath [project]
  (path (:source-path project) (str (:library-path project) "/*")))

(defn args [task args]
  (doseq [a args]
    (.. task createArg (setValue a))))

(defn env [task map]
  (doseq [[key val] map]
    (.addSysproperty task
     (make Environment$Variable {:key (name key) :value val}))))

(defn init-project [root]
  (compare-and-set! ant-project nil
    (make Project {:basedir root}
      (.init)
      (.addBuildListener
       (make NoBannerLogger
         {:message-output-level Project/MSG_INFO
          :output-print-stream  System/out
          :error-print-stream   System/err})))))