(ns sitetools.core
  (:require [clojure.string :as string]
            [goog.events :as evt]
            [goog.history.EventType :as hevt]
            [reagent.core :as r]
            [secretary.core :as secretary :refer-macros [defroute]]
            [reagent.debug :refer-macros [dbg log dev?]]
            [reagent.interop :as i :refer-macros [.' .!]])
  (:import goog.History
           goog.history.Html5History))

(when (exists? js/console)
  (enable-console-print!))

(defn rswap! [a f & args]
  ;; Like swap!, except that recursive swaps on the same atom are ok.
  {:pre [(satisfies? ISwap a)
         (ifn? f)]}
  (if a.rswapping
    (do (-> (or a.rswapfs
                (set! a.rswapfs (array)))
            (.push #(apply f % args)))
        (swap! a identity))
    (do (set! a.rswapping true)
        (try (swap! a (fn [state]
                        (loop [s (apply f state args)]
                          (if-some [sf (some-> a .-rswapfs .shift)]
                            (recur (sf s))
                            s))))
             (finally
               (set! a.rswapping false))))))


;;; Configuration

(declare page-content)

(defonce config (r/atom {:body [#'page-content]
                         :main-content [:div]
                         :pages #{}
                         :site-dir "outsite/public"
                         :css-infiles ["site/public/css/main.css"]
                         :css-file "css/built.css"
                         :js-file "js/main.js"
                         :main-div "main-content"
                         :default-title ""}))

(defonce history nil)

(defn demo-handler [state [id v1 v2 :as event]]
  (case id
    :set-content (let [title (if v2
                               (str (:title-prefix state) v2)
                               (str (:default-title state)))]
                   (assert (vector? v1))
                   (when r/is-client
                     (r/next-tick #(set! js/document.title title)))
                   (assoc state :main-content v1 :title title))
    :set-page (do (assert (string? v1))
                  (secretary/dispatch! v1)
                  (assoc state :page-name v1))
    :goto-page (do
                 (assert (string? v1))
                 (when r/is-client
                   (.setToken history v1 false)
                   (r/next-tick #(set! js/document.body.scrollTop 0)))
                 (recur state [:set-page v1]))
    state))

(defn dispatch [event]
  ;; (dbg event)
  (rswap! config demo-handler event)
  nil)

(defn add-page-to-generate [url]
  {:pre [(string? url)]
   :post [(map? %)]}
  (swap! config update-in [:pages] conj url))

(defn register-page [url comp title]
  {:pre [(re-matches #"/.*[.]html" url)
         (vector? comp)]}
  (secretary/add-route! url #(dispatch [:set-content comp title]))
  (add-page-to-generate url))


;;; History

(defn init-history [page]
  (when-not history
    (let [html5 (and page
                     (.isSupported Html5History)
                     (#{"http:" "https:"} js/location.protocol))]
      (doto (set! history
                  (if html5
                    (doto (Html5History.)
                      (.setUseFragment false)
                      (.setPathPrefix (-> js/location.pathname
                                          (string/replace
                                           (re-pattern (str page "$")) "")
                                          (string/replace #"/*$" ""))))
                    (History.)))
        (evt/listen hevt/NAVIGATE #(dispatch [:set-page (.-token %)]))
        (.setEnabled true))
      (when (and page (not html5) (-> history .getToken empty?))
        (.setToken history page)))))

(defn to-relative [f]
  (string/replace f #"^/" ""))


;;; Components

(defn link [props child]
  [:a (assoc props
             :href (-> props :href to-relative)
             :on-click #(do (.preventDefault %)
                            (dispatch [:goto-page (:href props)])))
   child])

(defn page-content []
  (let [{:keys [main-content]} @config]
    (assert (vector? main-content))
    main-content))


;;; Static site generation

(defn prefix [href page]
  (let [depth (-> #"/" (re-seq (to-relative page)) count)]
    (str (->> "../" (repeat depth) (apply str)) href)))

(defn danger [t s]
  [t {:dangerouslySetInnerHTML {:__html s}}])

(defn html-template [{:keys [title body-html timestamp page-conf
                             js-file css-file main-div]}]
  (let [main (str js-file timestamp)]
    (r/render-to-static-markup
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name 'viewport
               :content "width=device-width, initial-scale=1.0"}]
       [:base {:href (prefix "" (:page-name page-conf))}]
       [:link {:href (str css-file timestamp) :rel 'stylesheet}]
       [:title title]]
      [:body
       [:div {:id main-div} (danger :div body-html)]
       (danger :script (str "var pageConfig = "
                            (-> page-conf clj->js js/JSON.stringify) ";"))
       [:script {:src main :type "text/javascript"}]]])))

(defn gen-page [page-name conf]
  (dispatch [:set-page page-name])
  (let [b (:body conf)
        _ (assert (vector? b))
        bhtml (r/render-component-to-string b)]
    (str "<!doctype html>"
         (html-template (assoc conf
                               :page-conf {:page-name page-name}
                               :body-html bhtml)))))

(defn fs [] (js/require "fs"))
(defn path [] (js/require "path"))

(defn mkdirs [f]
  (let [items (as-> f _
                (.' (path) normalize _)
                (string/split _ #"/"))]
    (doseq [d (reductions #(str %1 "/" %2) items)]
      (when-not (.' (fs) existsSync d)
        (.' (fs) mkdirSync d)))))

(defn write-file [f content]
  (mkdirs (.' (path) dirname f))
  (.' (fs) writeFileSync f content))

(defn read-file [f]
  (.' (fs) readFileSync f))

(defn path-join [& paths]
  (apply (.' (path) :join) paths))

(defn read-files [files]
  (string/join "\n" (map read-file files)))

(defn write-resources [dir {:keys [css-file css-infiles]}]
  (write-file (path-join dir css-file)
              (read-files css-infiles)))


;;; Main entry points

(defn ^:export genpages [opts]
  (log "Generating site")
  (let [conf (swap! config merge (js->clj opts :keywordize-keys true))
        conf (assoc conf :timestamp (str "?" (js/Date.now)))
        {:keys [site-dir pages]} conf]
    (doseq [f pages]
      (write-file (->> f to-relative (path-join site-dir))
                  (gen-page f conf)))
    (write-resources site-dir conf))
  (log "Wrote site"))

(defn start! [site-config]
  (swap! config merge site-config)
  (when r/is-client
    (let [page-conf (when (exists? js/pageConfig)
                      (js->clj js/pageConfig :keywordize-keys true))
          conf (swap! config merge page-conf)]
      (init-history (:page-name conf))
      (r/render-component (:body conf)
                          (js/document.getElementById (:main-div conf))))))
