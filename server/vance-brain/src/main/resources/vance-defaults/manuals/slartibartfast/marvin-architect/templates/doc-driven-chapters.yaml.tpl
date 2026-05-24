name: {{ params.name }}
description: |
  {{ params.description }}
engine: marvin
params:
  rootTaskKind: PLAN
  maxPlanCorrections: 2
  defaultExecutionMode: SEQUENTIAL
  allowedSubTaskRecipes:
    - marvin-worker
  recipesOnlyViaExpand:
    - marvin-worker
  allowedExpandDocumentRefPaths:
    - {{ params.outlinePath }}
promptPrefix: |
  You are the {{ params.name }} PLAN node.{% if params.processGoalLabel %} The {{ params.processGoalLabel }} comes from the process goal.{% endif %}

  Emit EXACTLY {% if params.consolidate %}3{% else %}2{% endif %} children in this order. Use only WORKER and EXPAND_FROM_DOC; no other kinds.

  KIND 1 — WORKER marvin-worker (writes the outline document):
  {"taskKind":"WORKER",
   "goal":"{{ params.outlinePrompt | replace({'\"':'\\\"'}) }}",
   "taskSpec":{
     "recipe":"marvin-worker",
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"{{ params.outlinePath }}",
          "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"}}
     ]}}

  KIND 2 — EXPAND_FROM_DOC (one chapter per outline item):
  {"taskKind":"EXPAND_FROM_DOC",
   "goal":"Write one chapter per outline item.",
   "taskSpec":{
     "documentRef":{"path":"{{ params.outlinePath }}"},
     "treeMode":"FLAT",
     "childTemplate":{
       "taskKind":"WORKER",
       "recipe":"marvin-worker",
       "goal":"{{ params.chapterPromptTpl | replace({'\"':'\\\"'}) }}",
       "postActions":[
         {"tool":"doc_write_text",
          "args":{
            "path":"{{ params.chaptersDir }}/{% verbatim %}{{ node.goal | slug }}{% endverbatim %}.md",
            "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"}}
       ]}}}
{% if params.consolidate %}

  KIND 3 — AGGREGATE (consolidates chapters into the final document):
  {"taskKind":"AGGREGATE",
   "goal":"Consolidate the chapters into the final document.",
   "taskSpec":{
     "prompt":"{{ params.consolidatePrompt | replace({'\"':'\\\"'}) }} Language: {{ params.language }}.",
     "maxOutputChars":{{ params.maxOutputChars | default(20000) }},
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"{{ params.finalPath }}",
          "content":"{% verbatim %}{{ node.summary }}{% endverbatim %}"{% if params.outputTitleTpl %},
          "title":"{{ params.outputTitleTpl }}"{% endif %}}}
     ]}}
{% endif %}

  Output contract — EXACTLY {% if params.consolidate %}3{% else %}2{% endif %} children:
      {"children":[<KIND 1>,<KIND 2>{% if params.consolidate %},<KIND 3>{% endif %}]}

  Do not omit any KIND. The number of children MUST be {% if params.consolidate %}3{% else %}2{% endif %}.
