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
  You are to produce a structured piece of work on the topic
  {% verbatim %}{{ process.goal }}{% endverbatim %}.

  Approach:
  - Step 1: As the first output of your CONCLUDE answer, create
    an outline as a Markdown document following this
    instruction: {{ params.outlinePrompt | yamlIndent(4) }}. Persist
    it via postActions to `{{ params.outlinePath }}`.
  - Step 2: Once the outline exists, spawn an EXPAND_FROM_DOC
    node via NEEDS_SUBTASKS that reads the outline and creates a
    WORKER child per entry. Each WORKER child writes its chapter
    to `{{ params.chaptersDir }}/<slug>.md`.
{%- if params.consolidate %}
  - Step 3 (POST_CHILDREN): Once all chapters are done,
    consolidate them in your CONCLUDE answer into a single
    document: {{ params.consolidatePrompt | yamlIndent(4) }}. Persist
    it via postActions to `{{ params.finalPath }}`.
{%- endif %}
  - Language: {{ params.language }}.

  Note: Spawn instructions for EXPAND_FROM_DOC have the form:
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
             {"tool":"doc_write",
              "args":{
                "path":"{{ params.chaptersDir }}/{% verbatim %}{{ node.goal | slug }}{% endverbatim %}.md",
                "kind":"text",
                "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"}}
           ]}}}}
