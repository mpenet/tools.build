;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.deps.alpha.gen.pom :as pom]
    [clojure.tools.build :as build]
    [clojure.tools.build.file :as file]
    [clojure.tools.build.process :as process]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.namespace.find :as find])
  (:import
    [java.io File FileOutputStream FileInputStream BufferedInputStream BufferedOutputStream]
    [java.nio.file Path Paths Files LinkOption]
    [java.nio.file.attribute BasicFileAttributes]
    [java.util.jar Manifest Attributes$Name JarOutputStream JarEntry JarInputStream JarFile]
    [javax.tools ToolProvider DiagnosticListener]))

(set! *warn-on-reflection* true)

;; clean

(defn clean
  [_basis {:build/keys [target-dir]}]
  (file/delete target-dir))

;; compile-clj

(defn- write-compile-script
  ^File [target-dir class-dir nses]
  (let [script-file (jio/file target-dir (str (gensym "compile-") ".clj"))
        script (str/join (System/lineSeparator)
                 (concat [(str "(binding [*compile-path* " (pr-str class-dir) "]")]
                   (map #(str "  (compile '" % ")") nses)
                   ["  )"]))]
    (spit script-file script)
    script-file))

(defn compile-clj
  [{:keys [classpath] :as basis} {:build/keys [clj-paths target-dir class-dir ns-compile]}]
  (file/ensure-dir (jio/file class-dir))
  (let [srcs (deps/resolve-path-ref clj-paths basis)
        nses (or ns-compile
               (mapcat #(find/find-namespaces-in-dir (jio/file %) find/clj) srcs))
        compile-script (write-compile-script target-dir class-dir nses)

        cp-str (-> classpath keys (conj class-dir) deps/join-classpath)
        args ["java" "-cp" cp-str "clojure.main" (.getCanonicalPath compile-script)]
        exit (process/exec args)]
    (when (not= exit 0)
      {:error "Clojure compilation failed"})))

;; javac

(defn javac
  [{:keys [libs] :as basis} {:build/keys [class-dir java-paths javac-opts]}]
  (let [java-paths' (build/resolve-alias basis java-paths)]
    (when (seq java-paths')
      (let [class-dir (file/ensure-dir (jio/file class-dir))
            compiler (ToolProvider/getSystemJavaCompiler)
            listener (reify DiagnosticListener (report [_ diag] (println (str diag))))
            file-mgr (.getStandardFileManager compiler listener nil nil)
            classpath (str/join File/pathSeparator (mapcat :paths (vals libs)))
            options (concat ["-classpath" classpath "-d" (.getPath class-dir)] javac-opts)
            java-files (mapcat #(file/collect-files (jio/file %) :collect (file/suffixes ".java")) java-paths')
            file-objs (.getJavaFileObjectsFromFiles file-mgr java-files)
            task (.getTask compiler nil file-mgr listener options nil file-objs)
            success (.call task)]
        (when-not success
          {:error "Java compilation failed"})))))

;; pom

(defn sync-pom
  [basis {:build/keys [src-pom lib version class-dir] :or {src-pom "pom.xml"} :as params}]
  (let [group-id (or (namespace lib) (name lib))
        artifact-id (name lib)
        version (if (keyword? version)
                  (or (get params version) (build/resolve-alias basis version))
                  version)
        pom-dir (file/ensure-dir
                  (jio/file class-dir "META-INF" "maven" group-id artifact-id))]
    (pom/sync-pom
      {:basis basis
       :params {:src-pom src-pom
                :target-dir pom-dir
                :lib lib
                :version version}})
    (spit (jio/file pom-dir "pom.properties")
      (str/join (System/lineSeparator)
        ["# Generated by org.clojure/tools.build"
         (format "# %tc" (java.util.Date.))
         (format "version=%s" version)
         (format "groupId=%s" group-id)
         (format "artifactId=%s" artifact-id)]))))

;; include-resources

(defn include-resources
  [basis {:build/keys [resources class-dir]}]
  (let [classes (jio/file class-dir)
        dirs (build/resolve-alias basis resources)]
    (doseq [src-dir dirs]
      (file/copy (jio/file src-dir) classes))))

;; jar

(defn- add-jar-entry
  [^JarOutputStream output-stream ^String path ^File file]
  (let [dir (.isDirectory file)
        attrs (Files/readAttributes (.toPath file) BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" (into-array LinkOption []))
        path (if (and dir (not (.endsWith path "/"))) (str path "/") path)
        entry (doto (JarEntry. path)
                ;(.setSize (.size attrs))
                ;(.setLastAccessTime (.lastAccessTime attrs))
                (.setLastModifiedTime (.lastModifiedTime attrs)))]
    (.putNextEntry output-stream entry)
    (when-not dir
      (with-open [fis (BufferedInputStream. (FileInputStream. file))]
        (jio/copy fis output-stream)))

    (.closeEntry output-stream)))

(defn- copy-to-jar
  ([^JarOutputStream jos ^File root]
    (copy-to-jar jos root root))
  ([^JarOutputStream jos ^File root ^File path]
   (let [root-path (.toPath root)
         files (file/collect-files root :dirs true)]
     (run! (fn [^File f]
             (let [rel-path (.toString (.relativize root-path (.toPath f)))]
               (when-not (= rel-path "")
                 ;(println "  Adding" rel-path)
                 (add-jar-entry jos rel-path f))))
       files))))

(defn- fill-manifest!
  [^Manifest manifest props]
  (let [attrs (.getMainAttributes manifest)]
    (run!
      (fn [[name value]]
        (.put attrs (Attributes$Name. ^String name) value)) props)))

(defn jar
  [basis {:build/keys [lib version classifier main-class target-dir class-dir] :as params}]
  (let [version (if (keyword? version)
                  (or (get params version) (build/resolve-alias basis version))
                  version)
        jar-name (str (name lib) "-" version (if classifier (str "-" classifier) "") ".jar")
        jar-file (jio/file target-dir jar-name)
        class-dir (jio/file class-dir)]
    (let [manifest (Manifest.)]
      (fill-manifest! manifest
        (cond->
          {"Manifest-Version" "1.0"
           "Created-By" "org.clojure/tools.build"
           "Build-Jdk-Spec" (System/getProperty "java.specification.version")}
          main-class (assoc "Main-Class" (str main-class))))
      (with-open [jos (JarOutputStream. (FileOutputStream. jar-file) manifest)]
        (copy-to-jar jos class-dir)))))

;; uberjar

(defn- explode
  [^File lib-file out-dir]
  (if (str/ends-with? (.getPath lib-file) ".jar")
    (let [buffer (byte-array 1024)]
      (with-open [jis (JarInputStream. (BufferedInputStream. (FileInputStream. lib-file)))]
        (loop []
          (when-let [entry (.getNextJarEntry jis)]
            ;(println "entry:" (.getName entry) (.isDirectory entry))
            (let [out-file (jio/file out-dir (.getName entry))]
              (jio/make-parents out-file)
              (when-not (.isDirectory entry)
                (when (.exists out-file)
                  ;; TODO - run a merge process
                  (println "CONFLICT: " (.getName entry)))
                (let [output (BufferedOutputStream. (FileOutputStream. out-file))]
                  (loop []
                    (let [size (.read jis buffer)]
                      (if (pos? size)
                        (do
                          (.write output buffer 0 size)
                          (recur))
                        (.close output))))
                  (Files/setLastModifiedTime (.toPath out-file) (.getLastModifiedTime entry))))
              (recur))))))
    (file/copy lib-file out-dir)))

(defn uber
  [{:keys [libs] :as basis} {:build/keys [target-dir class-dir lib version main-class] :as params}]
  (let [version (if (keyword? version)
                  (or (get params version) (build/resolve-alias basis version))
                  version)
        uber-dir (file/ensure-dir (jio/file target-dir "uber"))
        manifest (Manifest.)
        lib-paths (conj (->> libs vals (mapcat :paths) (map #(jio/file %))) (jio/file class-dir))
        uber-file (jio/file target-dir (str (name lib) "-" version "-standalone.jar"))]
    (run! #(explode % uber-dir) lib-paths)
    (fill-manifest! manifest
      (cond->
        {"Manifest-Version" "1.0"
         "Created-By" "org.clojure/tools.build"
         "Build-Jdk-Spec" (System/getProperty "java.specification.version")}
        main-class (assoc "Main-Class" (str main-class))))
    (with-open [jos (JarOutputStream. (FileOutputStream. uber-file) manifest)]
      (copy-to-jar jos uber-dir))))