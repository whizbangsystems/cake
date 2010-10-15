(ns cake.tasks.deps
  (:use cake cake.core cake.ant cake.file
        clojure.contrib.prxml
        [clojure.java.io :only [writer]]
        [cake.project :only [group log]])
  (:import [java.io File]
           [org.apache.tools.ant.taskdefs Copy Delete ExecTask Move Property]
           [org.apache.ivy.ant IvyConfigure IvyReport IvyResolve IvyRetrieve
            IvyDeliver IvyPublish IvyMakePom IvyInstall IvyMakePom$Mapping]
           [org.apache.ivy.plugins.parser.xml XmlModuleDescriptorParser]
           [org.apache.ivy.plugins.resolver IBiblioResolver]))

(def *exclusions* nil)

(def artifact-pattern "[artifact]-[revision].[ext]")

(def local-pattern "[organisation]/[module]/([branch]/)[revision]/[type]s/[artifact].[ext]")

(def repositories
     [["clojure"           "http://build.clojure.org/releases"]
      ["clojure-snapshots" "http://build.clojure.org/snapshots"]
      ["clojars"           "http://clojars.org/repo/"]])

(defn os-name []
  (let [name (System/getProperty "os.name")]
    (condp #(.startsWith %2 %1) name
      "Linux"    "linux"
      "Mac OS X" "macosx"
      "SunOS"    "solaris"
      "Windows"  "windows"
      "unknown")))

(defn os-arch []
  (or (first (:arch *opts*))
      (*config* "project.arch")
      (let [arch (System/getProperty "os.arch")]
        (case arch
          "amd64" "x86_64"
          "i386"  "x86"
          arch))))

(defn make-resolver
  "Creates a maven compatible ivy resolver from a lein/cake repository."
  [[id url]]
  (doto (IBiblioResolver.)
    (.setRoot url)
    (.setName id)
    (.setM2compatible true)))

(defn add-resolvers
  "Adds default and project repositories."
  [settings project]
  (let [chain (.getResolver settings "main")]
    (doseq [r (map make-resolver (concat repositories
                                         (:repositories project)))]
      (.setSettings r settings)
      (.addResolver settings r)
      (.add chain r))))

(defn exclusion
  [dep]
  [:exclude {:org (group dep) :name (name dep)}])

(defn dependencies
  [deps default-conf]
  (for [[dep opts] deps]
    [:dependency
     (merge {:org (group dep) :name (name dep) :rev (:version opts)
             :conf (or (:conf opts) default-conf)}
            (select-keys opts [:transitive])
            (select-keys opts [:branch]))
     (map exclusion (concat *exclusions* (:exclusions opts)))]))

(defn make-ivy
  [project]
  (with-open [out (writer "ivy.xml")]
    (binding [*prxml-indent* 2
              *out* out]
      (prxml [:ivy-module {:version "2.0"}
              [:info {:organisation (:group-id project) :module (:name project) :revision (:version project)}]
              [:configurations
               [:conf {:name "master" :visibility "public"}]
               [:conf {:name "default" :visibility "public"}]
               [:conf {:name "develop" :visibility "private"}]]
              [:dependencies
               (dependencies (:dependencies project) "default->default")
               (dependencies (:dev-dependencies project) "develop->default")]]))))

(defn extract-native [jars dest]
  (doseq [jar jars]
    (ant Copy {:todir dest :flatten true}
         (add-zipfileset {:src jar :includes (format "native/%s/%s/*" (os-name) (os-arch))}))))

(defn retrieve-conf [conf dest]
  (ant IvyRetrieve {:conf conf :pattern (str dest "/" artifact-pattern) :sync true})
  (extract-native
   (fileset-seq {:dir dest :includes "*.jar"})
   (str dest "/native")))

(defn retrieve []
  (log "Fetching dependencies...")
  (retrieve-conf "default" "build/lib")
  (retrieve-conf "develop" "build/lib/dev")
  (when (.exists (file "build/lib"))
    (ant Delete {:dir "lib"})
    (ant Move {:file "build/lib" :tofile "lib" :verbose true}))
  (let [deps-changed (.getProperty *ant-project* "ivy.deps.changed")]
    (when (= "true" deps-changed)
      (log "Dependencies changed.  Restarting bake...")
      (invoke clean {})
      (bake-restart))))

(defn stale-deps? [deps-str deps-file]
  (or (not (.exists deps-file)) (not= deps-str (slurp deps-file))))

(defn ivy-properties [project]
  (let [defaults {"ivy.shared.default.artifact.pattern" local-pattern
                  "ivy.shared.default.ivy.pattern" local-pattern
                  "ivy.local.default.artifact.pattern" local-pattern
                  "ivy.local.default.ivy.pattern" local-pattern}
        properties (merge defaults (:ivy-properties project))]
    (doall (for [[k v] properties]
             (ant Property {:name k :value v})))))

(deftask resolve
  (let [properties     (ivy-properties *project*)
        configure-task (ant IvyConfigure {})
        settings       (.getReference *ant-project* "ivy.instance")
        ivy            (.getConfiguredIvyInstance settings configure-task)]
    (add-resolvers (.getSettings ivy) *project*))
  (make-ivy *project*)
  (ant IvyResolve {}))

(deftask deps
  "Fetch dependencies and dev-dependencies. Use 'cake deps force' to refetch."
  (let [deps-str  (prn-str (into (sorted-map) (select-keys *project* [:dependencies :dev-dependencies])))
        deps-file (file "lib" "deps.clj")]
    (if (or (stale-deps? deps-str deps-file) (= ["force"] (:deps *opts*)))
      (do (invoke resolve)
          (retrieve)
          (spit deps-file deps-str))
      (when (= ["force"] (:compile *opts*))
        (invoke clean {})))))

(defn make-mapping [task attrs]
  (let [mapping (.createMapping task)]
    (set-attributes! mapping attrs)))

(deftask pom #{resolve}
  "Create a pom file."
  (ant IvyMakePom {:ivy-file "ivy.xml" :pom-file "pom.xml" }
       (make-mapping {:conf "default" :scope "compile"})))

(defn opts-to-ant [m]
  (into {} (for [[k [v]] m]
             [k (condp = v
                    "true" true
                    "false" false
                    v)])))

(deftask deps-report #{resolve}
  "Generates a dependency report in various formats to build/reports/ivy."
  (let [defaults {:todir "build/reports/ivy"}
        options  (merge defaults (opts-to-ant *opts*))]
    (ant IvyReport options)))

(deftask publish #{resolve}
  "Publish this project to the local ivy repository."
  (let [defaults {:resolver "local"
                  :forcedeliver true
                  :overwrite true
                  :srcivypattern "ivy-[revision].xml"
                  :artifactspattern "[artifact]-[revision].[ext]"}
        options  (merge defaults (opts-to-ant *opts*))]
    (ant IvyPublish options)))

(deftask install-module
  "Installs a module from one repository to another."
  "Requires --organisation --module and --revision.
   Defaults --from=public and --to=local."
  ;; TODO: Allow org/mod/revision instead of named parameters.
  (let [defaults {:from "public"
                  :to "local"}
        options (merge defaults (opts-to-ant *opts*))]
    (ant IvyInstall options)))