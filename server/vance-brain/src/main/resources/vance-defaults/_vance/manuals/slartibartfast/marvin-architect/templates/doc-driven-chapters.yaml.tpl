name: {{ params.name }}
description: |
  {{ params.description | yamlIndent(2) }}
engine: marvin
params:
  model: default:analyze
  availableRecipes:
{%- for r in params.availableRecipes %}
    - {{ r }}
{%- endfor %}
  language: {{ params.language }}
promptPrefix: |
  Du sollst zum Thema {% verbatim %}{{ process.goal }}{% endverbatim %} eine strukturierte
  Ausarbeitung produzieren.

  Vorgehen:
  - Schritt 1: Erstelle in deiner CONCLUDE-Antwort als ersten
    Output eine Outline (Gliederung) als Markdown-Document mit
    folgender Anweisung: {{ params.outlinePrompt | yamlIndent(4) }}. Persistiere
    sie über postActions nach `{{ params.outlinePath }}`.
  - Schritt 2: Sobald die Outline existiert, spawnst du via
    NEEDS_SUBTASKS einen EXPAND_FROM_DOC-Knoten, der die Outline
    liest und pro Eintrag ein WORKER-Kind erzeugt. Jedes
    WORKER-Kind schreibt sein Kapitel nach `{{ params.chaptersDir }}/<slug>.md`.
{%- if params.consolidate %}
  - Schritt 3 (POST_CHILDREN): Wenn alle Kapitel fertig sind,
    konsolidiere sie in deiner CONCLUDE-Antwort zu einem
    Gesamtdokument: {{ params.consolidatePrompt | yamlIndent(4) }}. Persistiere
    es über postActions nach `{{ params.finalPath }}`.
{%- endif %}
  - Sprache: {{ params.language }}.

  Hinweis: Spawn-Anweisungen für EXPAND_FROM_DOC haben die Form:
    {"goal":"<kurz>",
     "taskKind":"EXPAND_FROM_DOC",
     "taskSpec":{
       "documentRef":{"path":"{{ params.outlinePath }}"},
       "treeMode":"FLAT",
       "childTemplate":{
         "taskKind":"WORKER",
         "goal":"{{ params.chapterPromptTpl }}",
         "taskSpec":{
           "postActions":[
             {"tool":"doc_write_text",
              "args":{
                "path":"{{ params.chaptersDir }}/{% verbatim %}{{ node.goal | slug }}{% endverbatim %}.md",
                "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"}}
           ]}}}}
