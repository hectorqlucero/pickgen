(defproject pickgen "0.1.0"
  :description "pickgen"
  :url "http://example.com/FIXME" ; Change me - optional
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/data.csv "1.1.1"]
                 [org.clojure/data.json "2.5.2"]
                 [org.slf4j/slf4j-simple "2.0.17"]
                 [org.clojars.hector/pickdict "0.3.1"]
                 [compojure "1.7.2"]
                 [hiccup "2.0.0"]
                 [buddy/buddy-hashers "2.0.167"]
                 [com.draines/postal "2.0.5"]
                 [cheshire "6.1.0"]
                 [clj-pdf "2.7.4"]
                 [ondrs/barcode "0.1.0"]
                 [pdfkit-clj "0.1.7"]
                 [cljfmt "0.9.2"]
                 [clj-jwt "0.1.1"]
                 [clj-time "0.15.2"]
                 [date-clj "1.0.1"]
                 [org.xerial/sqlite-jdbc "3.51.2.0"]
                 [ring/ring-core "1.15.3"]
                 [ring/ring-jetty-adapter "1.15.3"]
                 [ring/ring-defaults "0.7.0"]
                 [ring/ring-devel "1.15.3"]
                 [ring/ring-codec "1.3.0"]]
  :main ^:skip-aot pickgen.core
  :aot [pickgen.core]
  :plugins [[lein-ancient "0.7.0"]
            [lein-pprint "1.3.2"]]
  :uberjar-name "pickgen.jar"
  :target-path "target/%s"
  :ring {:handler pickgen.core
         :auto-reload? true
         :auto-refresh? false}
  :resource-paths ["resources"]
  :aliases {"migrate"  ["run" "-m" "pickgen.migrations" "--"]
            "database" ["run" "-m" "pickgen.models.cdb/database" "--"]
            "scaffold" ["run" "-m" "pickgen.engine.scaffold"]}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:source-paths ["src" "dev"]
                   :main pickgen.dev}})
