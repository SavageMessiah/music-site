(ns new
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import [java.time Instant ZoneId]
           java.time.format.DateTimeFormatter))

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
               (for [{:keys [title time mp3-name tags desc]} (->> tracks (sort-by :time) reverse)
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

(def conf-dir (fs/path (fs/xdg-config-home) "music-site"))

(defn ensure-deps []
  (for [dep ["rclone" "lame" "fzf"]]
    (when-not (fs/exists? (fs/path "/bin" dep))
      (println "no" dep "install found")
      (System/exit 1))))

(def conf (let [conf-file (fs/path conf-dir "config.edn")]
            (if (fs/exists? conf-file)
              (-> conf-file
                  fs/file
                  slurp
                  edn/read-string)
              {})))

(defn write-tracks [dir tracks]
  (with-open [w (io/writer (fs/file dir "tracks.edn"))]
    (let [tracks (->> tracks
                      (map (fn [track]
                             (update track :time #(.toEpochMilli %))))
                      (sort-by :time)
                      vec)]
      (pp/pprint tracks w))))

(defn fix-tags [tags]
  (->> tags
       (map keyword)
       set))

(defn fetch-tracks []
  (println "Fetching remote tracks.edn")
  (->> (http/get "http://imayeti.com/music/tracks.edn")
       :body
       edn/read-string
       (mapv (fn [track]
               (-> track
                   (update :time #(Instant/ofEpochMilli %))
                   (update :tags fix-tags))))))

(defn tracks->tags [tracks]
  (->> tracks
       (mapcat :tags)
       set))

(defn fzf [items]
  (let [in (str/join "\n" items)
        {:keys [out exit]} @(p/process {:in in
                                        :err :string
                                        :out :string} "fzf")]
    (when-not (zero? exit)
      (println "fzf exit:" exit)
      (System/exit 0))
    (str/trim out)))

(defn pick-file [dir]
  (->> (fs/glob dir "*.wav")
       sort
       reverse
       fzf
       fs/path))

(defn encode-mp3 [work-dir source-file]
  (println "encoding mp3")
  (let [out-file (fs/path work-dir "temp.mp3")]
    @(p/shell {:out :string :err :string} "lame -V 0" source-file out-file)
    out-file))

(def separator (str/join (concat (repeat 50 "-") ["\n"])))

(defn track->edit [reminder-tags {:keys [title desc tags time]}]
  (with-open [out (java.io.StringWriter.)]
    (doto out
      (.write ";; ")
      (.write (pr-str reminder-tags))
      (.write "\n"))
    (pp/pprint {:title title
                :tags tags
                :time (str time)}
               out)
    (doto out
      (.write separator)
      (.write desc))
    (str out)))

(defn edit->track [txt]
  (let [[edn desc] (str/split txt #"(?m)^-+$")
        track (edn/read-string edn)]
    (-> track
        (update :time #(Instant/parse %))
        (assoc :desc (str/trim desc)))))

(defn edit-track [work-dir reminder-tags track]
  (println "Writing track out for editing")
  (let [edit (track->edit reminder-tags track)
        work-file (fs/file work-dir "temp.txt")]
    (spit work-file edit)
    @(p/shell "nvim" (str work-file))
    (let [altered (->> work-file
                       slurp
                       edit->track)
          new-mp3-name (-> altered
                           :title
                           (str/replace #" " "_")
                           (str ".mp3"))
          edited (merge track (assoc altered :mp3-name new-mp3-name))]
      (when (= (dissoc edited :mp3-name) (dissoc track :mp3-name))
        (println "file not changed, aborting")
        (System/exit 0))
      edited)))

(defn new-track [source-file]
  (let [title (-> source-file
                  fs/strip-ext
                  fs/file-name)]
    {:title (str/replace title #"_" " ")
     :mp3-name (str title ".mp3")
     :tags #{}
     :time (str (Instant/now))
     :desc ""}))

(defn select-track-idx-to-edit [tracks]
  (let [title->idx (zipmap (map :title tracks) (range))]
    (->> title->idx
         keys
         sort
         reverse
         fzf
         title->idx)))

(defn edit-existing-track [work-dir tracks]
  (let [idx (select-track-idx-to-edit tracks)
        reminder-tags (tracks->tags tracks)]
    (update tracks idx #(edit-track work-dir reminder-tags %))))

(defn prep-for-upload [work-dir tracks]
  (println "Writing tracks.edn for upload")
  (write-tracks work-dir tracks)
  (println "Generating and writing index.html")
  (->> tracks
       page
       str
       (spit (fs/file work-dir "index.html"))))

(defn prep-mp3 [work-dir mp3-name]
  (fs/create-dirs (fs/path work-dir "tracks"))
  (println "Moving mp3 to final name " mp3-name)
  (fs/move (fs/path work-dir "temp.mp3")
           (fs/path work-dir "tracks" mp3-name)))

(defn upload [work-dir dry-run?]
  (let [includes ["/index.html" "/tracks.edn" "/tracks/*.mp3"]
        cmd (vec (concat ["rclone" "copy" "-P" #_"-vv"]
                         (mapcat (fn [i] ["--include" i]) includes)
                         (when dry-run?
                           ["--dry-run"])
                         [work-dir "b2:imayeti-com/music"]))]

    @(p/shell {:cmd cmd})))

(defn command [body]
  (fn [{{:keys [dry-run] :as opts} :opts}]
    (when dry-run
      (println "dry run, not uploading"))
    (ensure-deps)
    (fs/with-temp-dir [work-dir {}]
      (let [old-tracks (future (fetch-tracks))
            new-tracks (body work-dir old-tracks opts)]
        (prep-for-upload work-dir new-tracks)
        (upload work-dir dry-run)))))

(defn new [work-dir tracks {:keys [dir]}]
  (let [dir (or dir (:recording-dir conf))
        _ (println "Starting a new track selected from" dir)
        track-file (pick-file dir)
        mp3-file (future (encode-mp3 work-dir track-file))
        reminder-tags (tracks->tags @tracks)
        {:keys [mp3-name] :as track} (edit-track work-dir reminder-tags (new-track track-file))]
    (println "waiting for mp3 encoding")
    @mp3-file
    (println "done")
    (prep-mp3 work-dir mp3-name)
    (conj @tracks track)))

(defn edit [work-dir tracks _]
  (println "editing an existing track")
  (edit-existing-track work-dir @tracks))

(def common-spec {:dry-run {:desc "Don't upload anything"}})

(def new-spec (merge common-spec {:dir {:ref "<dir>"
                                        :default (:recording-dir conf)
                                        :desc "Pick new track wav file from this dir."}}))

(defn help [_]
  (println (str/trim "
Usage: track <subcommand> <options>

Subcommands:

new - Encode and describe a new track"))
  (println (cli/format-opts {:spec new-spec}))
  (println)
  (println (str/trim "
edit - Edit the description of an existing track"))
  (println (cli/format-opts {:spec common-spec})))

(def commands
  [{:cmds ["new"] :fn (command new) :args->opts [:dir] :spec new-spec}
   {:cmds [] :fn help}
   {:cmds ["edit"] :fn (command edit) :spec common-spec}])

(cli/dispatch commands *command-line-args*)
