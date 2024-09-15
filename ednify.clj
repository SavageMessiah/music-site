(ns ednify
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn ensure-yj []
  (when-not (fs/exists? "/bin/yj")
    (println "no yj install found")
    (System/exit 1)))

(defn track-files [in]
  (->> (fs/list-dir in)
       (filter fs/directory?)
       (mapcat #(fs/list-dir % "*.mp3"))
       (remove fs/hidden?)))

(def section->tags {:ambient [:ambient]
                    :drone [:noise]
                    :feedback [:experiment :feedback]
                    :musical [:musical]})

(defn file->track-data [path]
  (let [toml (as-> path $
                   (fs/strip-ext $)
                   (str $ ".toml")
                   (fs/path $))
        data (if (fs/exists? toml) 
               (-> (p/shell {:in (fs/file toml)
                       :out :string}
                      "/bin/yj -t")
                 :out
                 (json/parse-string true))
               {})
        section (-> path fs/parent fs/file-name keyword)
        simple-title (-> path fs/file-name fs/strip-ext)]
    {:mp3 path
     :section section
     :simple-title simple-title
     :title (or (:Title data) simple-title)
     :tags (or (:Tags data) (section->tags section))
     :desc (or (:desc data) (:Desc data))
     :time (fs/last-modified-time path)}))

(defn maybe-rename-track [out {:keys [mp3 simple-title] :as track}]
  (let [mp3-name (str (str/replace simple-title " " "_") ".mp3")
        target (fs/path out "tracks" mp3-name)]
    (if (fs/exists? target)
      (do (println target "exists, renaming")
          (maybe-rename-track out (update track :simple-title #(str % "_"))))
      (assoc track :target-mp3 target :mp3-name mp3-name))))

(defn add-track [out track]
  (let [{:keys [mp3 target-mp3] :as track} (maybe-rename-track out track)
        track (-> track
                  (dissoc :mp3 :target-mp3)
                  (update :time fs/file-time->millis))]
    (println "Copying" (str mp3) "to" (str target-mp3))
    (fs/copy mp3 target-mp3)
    track))

(defn find-tracks [in]
  (->> (track-files in)
       (map file->track-data)))

(defn ednify [in out]
  (fs/create-dirs (fs/path out "tracks"))
  (let [tracks (->> (find-tracks in) 
                (remove #(#{:exp :other :weird} (:section %)))
                (map (partial add-track out))
                (sort-by :time)
                vec)] 
    (with-open [w (io/writer (str out "/tracks.edn"))]
      (pp/pprint tracks w))
    (println "Copied" (count tracks))))

(ensure-yj)
(apply ednify *command-line-args*)
