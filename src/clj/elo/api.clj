(ns elo.api
  (:gen-class)
  (:require [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [compojure.core :refer [defroutes GET POST]]
            [elo.auth :refer [basic-auth-backend with-basic-auth]]
            [elo.core :as core]
            [elo.db :as db]
            [environ.core :refer [env]]
            [hiccup.core :as hiccup]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as r-def]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.resource :as resources]
            [ring.util.response :as resp])
  (:import (java.util UUID)))

(def ^:private default-port 3000)

(defn- get-port
  []
  (Integer. (or (env :port) default-port)))

(defn- cache-buster
  [path]
  ;; fallback to a random git sha when nothing is found
  (format "%s?git_sha=%s"
          path
          (:heroku-slug-commit env (str (UUID/randomUUID)))))

(defn ga-js
  []
  (format "<!-- Global site tag (gtag.js) - Google Analytics -->
<script async src='https://www.googletagmanager.com/gtag/js?id=UA-123977600-1'></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());

  gtag('config', '%s');
</script>
" (env :google-analytics-key)))

(defn- as-json
  [response]
  (resp/content-type response "application/json"))

(def body
  [:html
   [:head [:meta {:charset "utf-8"
                  :description "FIFA championship little helper"}]
    [:title "FIFA championship"]

    [:link {:href (cache-buster "css/react-datepicker.css")
            :rel "stylesheet"
            :type "text/css"}]

    [:link {:rel "stylesheet"
            :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/css/bootstrap.min.css"
            :integrity "sha384-Gn5384xqQ1aoWXA+058RXPxPg6fy4IWvTNh0E263XmFcJlSAwiGgFAW/dAiS6JXm"
            :crossorigin "anonymous"}]

    [:link {:href (cache-buster "css/screen.css")
            :rel "stylesheet"
            :type "text/css"}]

    (when (env :google-analytics-key)
      (ga-js))

    ;; [:script {:src "https://code.jquery.com/jquery-3.2.1.slim.min.js"
    ;;           :integrity "sha384-KJ3o2DKtIkvYIK3UENzmM7KCkRr/rE9/Qpg6aAZGJwFDMVNA/GpGFF93hXpG5KkN"
    ;;           :crossorigin "anonymous"
    ;;           :async true}]

    ;; [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.12.9/umd/popper.min.js"
    ;;           :integrity "sha384-ApNbgh9B+Y1QKtv3Rn7W3mgPxhU9K/ScQsAP7hUibX39j7fakFPskvXusvfa0b4Q"
    ;;           :crossorigin "anonymous"
    ;;           :async true}]

    ;; [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/js/bootstrap.min.js"
    ;;           :integrity "sha384-JZR6Spejh4U02d8jOt6vLEHfe/JQGiRRSQQxSfFWpi1MquVdAyjUar5+76PVCmYl"
    ;;           :crossorigin "anonymous"
    ;;           :async true}]
]

   [:body
    [:div {:id "app"}]
    [:script {:src (cache-buster "js/compiled/app.js")}]
    [:script "elo.core.init();"]]])

(defn store!
  [{:keys [params]}]
  (as-json
   (let [result (db/store! params)]
     {:status 201
      :body result})))

(defn register!
  "Adds a new user to the platform, authenticated with basic Auth"
  [{:keys [params] :as request}]
  (with-basic-auth request
    (as-json
     (resp/response (db/register! params)))))

(defn home
  []
  (resp/content-type
   (resp/response
    (hiccup/html body))

   "text/html"))

(defn games
  []
  (as-json
   (resp/response (reverse (db/load-games)))))

(defn player->ngames
  [games]
  (frequencies
   (flatten
    (for [g games]
      ((juxt :p1 :p2) g)))))

(defn get-rankings
  "Return all the rankings"
  []
  (as-json
   (resp/response
    (let [games (db/load-games)
          norm-games (map core/normalize-game games)
          rankings (core/compute-rankings norm-games (map :id (db/load-players)))
          ngames (player->ngames games)]

      (reverse
       (sort-by :ranking
                (for [[k v] rankings]
                  {:id k :ranking v :ngames (get ngames k 0)})))))))

(defn get-players
  []
  (as-json
   (resp/response (db/load-players))))

(defroutes app-routes
  (GET "/" [] (home))
  (GET "/games" [] (let [games (db/load-games)]
                     {:status 200
                      :body games}))

  (GET "/rankings" [] (get-rankings))
  (GET "/players" [] (get-players))
  (POST "/store" request (store! request))
  (POST "/add-player" request (register! request)))

(def app
  (-> app-routes
      (resources/wrap-resource "public")
      (r-def/wrap-defaults r-def/api-defaults)
      (wrap-authorization basic-auth-backend)
      (wrap-authentication basic-auth-backend)
      wrap-keyword-params
      wrap-json-params
      wrap-json-response))

(defn -main [& args]
  (jetty/run-jetty app {:port (get-port)}))
