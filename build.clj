(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.olical/clojure-dap)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [basis @basis]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/Olical/clojure-dap"
                        :connection "scm:git:git://github.com/Olical/clojure-dap.git"
                        :developerConnection "scm:git:ssh://git@github.com/Olical/clojure-dap.git"
                        :tag (str "v" version)}})
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file}))
  (println (str "Built " jar-file)))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact (b/resolve-path jar-file)
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
