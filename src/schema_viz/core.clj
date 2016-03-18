(ns schema-viz.core
  (:require [clojure.string :as str]
            [schema.core :as s]
            [schema-tools.walk :as stw]
            [schema-tools.core :as st]
            [rhizome.viz :as viz]))

;;
;; Definitions
;;

(defrecord SchemaDefinition [schema fields relations])

(defrecord SchemaReference [schema]
  s/Schema
  (spec [_]
    (s/spec schema))
  (explain [_]
    (s/schema-name schema))
  stw/WalkableSchema
  (-walk [this inner outer]
    (outer (with-meta (->SchemaReference (inner (:schema this))) (meta this)))))

;;
;; Walkers
;;

(defn- full-name [path]
  (->> path (map name) (map str/capitalize) (apply str) symbol))

(defn- plain-map? [x]
  (and (map? x) (and (not (record? x)))))

; TODO: does not understand direct nested record values, e.g. (s/maybe (s/maybe {:a s/Str}))
(defn- named-subschemas [schema]
  (letfn [(-named-subschemas [path schema]
            (stw/walk
              schema
              (fn [x]
                (cond
                  (map-entry? x) (let [[k v] x
                                       name (s/schema-name (st/schema-value v))]
                                   [k (-named-subschemas
                                        (if name [name]
                                                 (into path
                                                       [:$
                                                        (if (s/specific-key? k)
                                                          (s/explicit-schema-key k)
                                                          (gensym (pr-str k)))])) v)])
                  (s/schema-name x) (-named-subschemas [(s/schema-name x)] x)
                  :else (-named-subschemas path x)))
              (fn [x]
                (if (and (plain-map? x) (not (s/schema-name x)))
                  (with-meta x {:name (full-name path), ::sub-schema? true})
                  x))))]
    (-named-subschemas [(s/schema-name schema)] schema)))

(defn- with-sub-schemas-references [schemas]
  (->> schemas
       (stw/postwalk
         (fn [x]
           (if (s/schema-name x)
             (->SchemaReference x)
             x)))
       (mapv :schema)))

(defn- collect-schemas [schemas]
  (let [name->schema (atom {})]
    (stw/prewalk
      (fn [schema]
        (when-let [name (s/schema-name schema)]
          (swap!
            name->schema update-in [name]
            (fn [x] (conj (or x #{}) schema))))
        schema)
      schemas)
    ;; TODO: handle duplicate names here
    (->> @name->schema vals (map first))))

;; TODO: dummy implementation, just looks for a first schema
(defn- peek-schema [schema]
  (let [peeked (atom nil)]
    (->> schema
         (stw/prewalk
           (fn [x]
             (if (and (plain-map? x) (s/schema-name x))
               (do (if-not @peeked (reset! peeked x)) x)
               x))))
    @peeked))

;;
;; Models
;;

(defn- extract-schema-var [x]
  (and (var? x) (s/schema-name @x) @x))

(defn- schema-definition [schema]
  (when (s/schema-name schema)
    (let [fields (for [[k v] schema :let [peeked (peek-schema v)]]
                   [k v peeked])]
      (->SchemaDefinition
        schema
        (->> fields (map butlast))
        (->> fields (keep last) set)))))

(defn- extract-relations [{:keys [schema relations]}]
  (map (fn [r] [schema r]) relations))

(defn- safe-explain [x]
  (try
    (s/explain x)
    (catch Exception _ x)))

(defn- explain-key [key]
  (if (s/specific-key? key)
    (str
      (s/explicit-schema-key key)
      (if (s/optional-key? key) "?"))
    (safe-explain key)))

(defn- explain-value [value]
  (str (or (s/schema-name value) (safe-explain value))))

(defn- schema-definitions [ns]
  (->> ns
       ns-publics
       vals
       (keep extract-schema-var)
       (map named-subschemas)
       with-sub-schemas-references
       collect-schemas
       (mapv schema-definition)))

;;
;; DOT
;;

(defn- wrap-quotes [x] (str "\"" x "\""))

(defn- wrap-escapes [x] (str/escape x {\> ">", \< "<", \" "\\\""}))

(defn- dot-class [{:keys [fields?]} {:keys [schema fields]}]
  (let [{name :name sub-schema? ::sub-schema?} (meta schema)
        fields (for [[k v] fields] (str "+ " (explain-key k) " " (-> v explain-value wrap-escapes)))]
    (str (wrap-quotes name) " [label = \"{" name (if fields? (str "|" (str/join "\\l" fields))) "\\l}\"]")))

(defn- dot-relation [[from to]]
  (str (wrap-quotes (s/schema-name from)) " -> " (wrap-quotes (s/schema-name to)) " [dirType = \"forward\"]"))

(defn- dot-node [node data]
  (str node "[" (str/join ", " (map (fn [[k v]] (str (name k) "=" (pr-str v))) data)) "]"))

(defn- dot-package [options definitions]
  (let [relations (mapcat extract-relations definitions)]
    (str/join
      "\n"
      (concat
        ["digraph {"
         "fontname = \"Bitstream Vera Sans\""
         "fontsize = 12"
         (dot-node "node" {:fontname "Bitstream Vera Sans"
                           :fontsize 12
                           :shape "record"
                           :style "filled"
                           :fillcolor "#ccffcc"
                           :color "#558855"})
         (dot-node "edge" {:arrowhead "diamond"})]
        (map (partial dot-class options) definitions)
        (map dot-relation relations)
        ["}"]))))

;;
;; Visualization
;;

(def +defaults+ {:fields? true})

(defn visualize-schemas
  ([]
   (visualize-schemas {}))
  ([options]
   (let [options (merge {:ns *ns*} +defaults+ options)]
     (->> (:ns options)
          schema-definitions
          (dot-package options)
          viz/dot->image
          viz/view-image))))

(defn save-schemas
  ([file]
   (save-schemas file {}))
  ([file options]
   (let [options (merge {:ns *ns*} +defaults+ options)]
     (-> (:ns options)
         schema-definitions
         (dot-package options)
         viz/dot->image
         (viz/save-image file)))))
