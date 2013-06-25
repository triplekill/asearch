(ns scienscan.handler
  (:use compojure.core
        scienscan.core)
  (:require
   [clojure.string :as string]
   [compojure.handler :as handler]
   [compojure.route :as route]
   [noir.session :as session]
   [cheshire [core :as json]]
   [utils.core :as u]
   [mas-api.core :as mas]
   [topic-maps.core :as tmaps]
   [scienscan.html :as html]
   [scienscan.submaps :as submaps]))

(def options
  {:n-topics 5
   :query "statistical relational learning"
   :ms-timeout 30000
   :n-results 100
   :submap-fn submaps/best-by-greedy-coverage})

(defn- cache-filename [query]
  (str "./resources/data/query-cache/" query ".clj"))

(defn cache-data!
 ([data]
  (cache-data! data (:query data)))
 ([data query]
  (do
   (spit (cache-filename query) data)
   data)))

(defn- cached-data [query]
  (read-string (slurp (cache-filename query))))

(def query-cache
  (u/val-map cached-data [(options :query)]))

(defn validate [{:keys [query n-topics] :as params}]
  (let [default-params (-> options
                           (select-keys [:query :n-topics])
                           (update-in [:n-topics] str))]
    (-> params
        (cond-> (string/blank? query) (dissoc :query)
                (nil? n-topics) (dissoc :n-topics))
        (#(merge default-params %))
        (update-in [:n-topics] #(Integer/parseInt %)))))

(defn get-query-results [query]
  {:pre [(not (string/blank? query))]}
  (let [timeout (options :ms-timeout)
        n-results (options :n-results)]
    (u/fmap
     (mas/search-papers query :end n-results :timeout timeout)
     (partial map map->Paper))))

(defn build-topic-map [results]
  {:pre [(instance? utils.core.Result results)]}
  (u/fmap results submaps/build-topic-map))

(defn select-submap [topic-map n-topics]
  {:pre [(instance? utils.core.Result topic-map)
         (not (nil? n-topics))
         (or (u/fail? topic-map)
             (>= (count (tmaps/get-topics (:value topic-map))) n-topics))]}
  (u/fmap topic-map #(submaps/best-by-greedy-coverage % n-topics)))

(defn compute-svg [submap]
  {:pre [(instance? utils.core.Result submap)]}
  (u/fmap submap tmaps/topics2svg))

(defn build-js-data [topic-map]
  {:pre [(not (nil? topic-map))
         (instance? topic_maps.core.TopicMap topic-map)]}
  (let [topics (tmaps/get-topics topic-map)
        topic-titles (->> topics
                          (u/val-map (partial tmaps/proper-docs topic-map))
                          (u/map-key :id))
        rel (for [topic topics
                  child-topic (tmaps/child-topics topic-map topic)]
              (mapv (comp str :id) [child-topic topic]))
        title-results (:doc-map topic-map)]
    {:topic-titles topic-titles
     :rel rel
     :title-results title-results
     :topic-index (u/key-map :id topics)}))

(defn new-topic-map-data [query]
  {:pre [(not (string/blank? query))]}
  (println "Preparing new data:" query)
  (-> {:query query}
      (u/assocf get-query-results :query :results)
      (u/assocf build-topic-map :results :topic-map)
      (u/assocf count (comp tmaps/get-topics :value :topic-map) :n-topics-max)))

(defn get-session-data [query]
  (let [data (session/get :session-data)]
    (when (= query (:query data))
      (println "Hit the session data:" query)
      data)))

(defn get-query-cache [query]
  (when-let [data (query-cache query)]
    (println "Hit the query cache:" query)
    data))

(defn topic-map-data [query]
  (or (get-session-data query)
      (get-query-cache query)
      (new-topic-map-data query)))

(defn prepare-data [{:keys [query n-topics] :as request}]
  {:pre [(not (string/blank? query))
         (not (nil? n-topics))]}
  (-> request
      (merge (topic-map-data query))
      (assoc :n-topics n-topics)
      (u/assocf #(select-submap % n-topics) :topic-map :submap)
      (u/assocf compute-svg :submap :svg)
      (u/assocf #(u/fmap % build-js-data) :submap :json)))
  
(defn render-json-svg [{:keys [json svg]}]
  (json/encode
   {:svg (:value svg)
    :json (:value json)}))

(defn render-whole-page [data]
  (html/layout (html/search-page-html data)))

(defroutes app-routes
  (GET "/" [query n-topics]
       (println "Request:" query n-topics)
       (println (session/get query))
       (-> {:query query :n-topics n-topics}
           validate
           prepare-data
           (doto (#(session/put! :session-data %)))
;           cache-data!
           render-whole-page))
  (POST "/refine" [query n-topics]
        (println query n-topics)
        (-> {:query query :n-topics n-topics}
            validate
            prepare-data
            render-json-svg))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> (handler/site app-routes)
      (session/wrap-noir-session)))
