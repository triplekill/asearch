(ns wminer.core
  (:import [org.wikipedia.miner.util WikipediaConfiguration]
           [org.wikipedia.miner.model Wikipedia Article Category Page Page$PageType]
           [org.wikipedia.miner.annotation Disambiguator TopicDetector Topic]
           [org.wikipedia.miner.annotation.weighting LinkDetector]
           [org.wikipedia.miner.comparison ArticleComparer])
  (:require (clojure.java [io :as io])
            (utils [core :as u]
                   [text :as t])
            (wiki-api [core :as wapi])
            (clojure [string :as string])))

(def resources
  (delay 
    (let [wiki-conf (WikipediaConfiguration. (io/file "/Users/dmirylenka/code/asearch-modular/wminer/resources/wikipedia-template.xml")) 
          wikipedia (Wikipedia. wiki-conf false)
          disambiguator (Disambiguator. wikipedia)
          topic-detector (TopicDetector. wikipedia disambiguator true false)
          link-detector (LinkDetector. wikipedia)
          art-comparer (ArticleComparer. wikipedia)]
      {:wikipedia wikipedia
       :topic-detector topic-detector
       :link-detector link-detector
       :art-comparer art-comparer})))

(defn ^Wikipedia wikipedia []
  (@resources :wikipedia))

(defn ^TopicDetector topic-detector []
  (@resources :topic-detector))

(defn ^LinkDetector link-detector []
  (@resources :link-detector))

(defn ^ArticleComparer art-comparer []
  (@resources :art-comparer))

(defn ->wapi-article [^Article article]
  (wapi/->Article (.getId article) (.getTitle article)))

(defn ^Article ->wminer-article [article]
  (.getPageById (wikipedia) (:id article)))

(defn ->wapi-category [^Category category]
  (wapi/->Category (.getId category) (.getTitle category)))

(defn ^Category ->wminer-category [category]
  (.getPageById (wikipedia) (:id category)))

(defn get-wiki-topics
  "Returns a collection of wikiminer Topics detected in a string."
  [^String string & [probability]]
  (let [all-topics (.getTopics (topic-detector) string nil)]
    (if-not probability
      all-topics
      (.getBestTopics (link-detector) all-topics probability))))

(defn- get-title [^Topic topic]
  (.getTitle topic))

(defn get-topics
  "Same as get-wiki-topics, but returns only topic titles in the format as in the URLs of Wikipedia articles."
  [string & [probability]]
  (map get-title (get-wiki-topics string probability)))

(defn article-by-title [title]
  (.getArticleByTitle (@resources :wikipedia) title))

(defn out-links [title]
  (map (memfn getTitle)
       (.getLinksOut (article-by-title title))))

(defn cat-by-title [title]
  (.getCategoryByTitle (@resources :wikipedia) title))

(defn page-by-id [title]
  (.getPageById (@resources :wikipedia) title))

(def page-types
  {:article Page$PageType/article
   :category Page$PageType/category})

(defn topic-out-links
  "If topic is a page gets its out-links in Wikipedia, if it is a category, get the out-links of its main page."
  [topic-title]
  (let [article-candidate (article-by-title topic-title)
        stem (comp t/stem t/string->words)
        main-article-by-cat (fn [category-title]
                              (let [category (cat-by-title category-title)
                                    cat-articles (.getChildArticles category)
                                    stemmed-title (stem topic-title)
                                    main-article? #(= stemmed-title (stem (.getTitle %)))]
                                (first (filter main-article? cat-articles))))

        article (or article-candidate (main-article-by-cat topic-title))]
    (if article
      (->> article .getLinksOut (map (memfn getTitle)) #_(map string/lower-case))
      (throw (Exception. (str "Could not find neither article nor category with the main article:" topic-title))))))

(deftype WikiService []
  wapi/IWikiService
  (-annotate [this docs]
    (for [doc docs 
          :let [docstr (wapi/doc-string doc)]
          topic (get-wiki-topics docstr 1e-4)
          position (.getPositions topic)]
      (wapi/->DocArticleLink doc (->wapi-article topic)
                             (.substring docstr (.getStart position) (.getEnd position))
                             (.getWeight topic))))
  (-relatedness [this article-pairs]
    (for [[a1 a2] article-pairs
          :let [wminer-a1 (->wminer-article a1)
                wminer-a2 (->wminer-article a2)
                score (.getRelatedness (art-comparer) wminer-a1 wminer-a2)]]
      (wapi/->ArticleRel #{a1 a2} score)))
  (-article-categories [this article]
    (map ->wapi-category (.getParentCategories (->wminer-article article))))
  (-cat-relations [this categories]
    (let [wiki (wikipedia)
          categories (set categories)
          parent-cats #(map ->wapi-category (.getParentCategories (->wminer-category %)))]
      (for [cat categories
            parent (filter categories (parent-cats cat))] 
        [cat parent]))))

(def service (WikiService.))
