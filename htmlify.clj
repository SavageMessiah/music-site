(ns htmlify
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import [java.time Instant ZoneId]
           java.time.format.DateTimeFormatter))

(defn read-tracks [in-file]
  (->> in-file
       slurp
       edn/read-string
       (map (fn [track]
              (update track :time #(Instant/ofEpochMilli %))))))

(def formatter (.withZone (DateTimeFormatter/ofPattern "(MMMM dd, yyyy)") (ZoneId/of "EST" ZoneId/SHORT_IDS)))

(defn page [tracks]
  (let [tags (->> tracks (mapcat :tags) (map name) set sort)
        title "Ridiculous Noise Distribution Center"]
    (h/html [:html
             [:head
              [:title title]
              [:link {:rel "stylesheet"
                      :href "https://fonts.googleapis.com/css?family=Share+Tech+Mono"}]
              [:link {:rel "stylesheet" :href "style.css"}]
              [:script {:type "text/javascript" :src "filter.js"}]]
             [:body 
              [:header.container
               [:h1 "Ridiculous Noise Distribution Center"]
               [:h2 [:div [:span#count]]]
               [:h5 "Check tags and/or type a title fragment to hide all non-matching tracks."]
               [:div.tag_select
                (for [tag tags]
                  [:label
                   [:input.tagselect {:type "checkbox" :value tag}]
                   tag])]
               [:input#filter {:type "text"}]
               [:button#clear "clear"]]
               [:main.container
                (for [{:keys [title time mp3-name tags desc]} tracks
                      :let [tags (->> tags (map name) (map #(str "#" %)) (str/join " "))
                            mp3-path (str "tracks/" mp3-name)]]
                  [:article.track {:id mp3-name
                                   :data-tags tags
                                   :data-title title}
                   [:header
                    [:a {:href (str "#" mp3-name)} "🔗"]
                    [:span.title title]
                    [:span.time (.format formatter time)]
                    [:audio {:preload "none"
                             :controls true}
                     [:source {:src mp3-path
                               :type "audio/mpeg"}]
                     [:a {:href mp3-path} "mp3"]]
                    [:span.tags tags]]
                   [:p desc]])]]])))

(defn htmlify [in-file out-file]
  (->> (read-tracks in-file)
       page
       str
       (spit out-file)))


(apply htmlify *command-line-args*)
