name: {{ params.name }}
description: |
  {{ params.description }}
engine: marvin
params:
  rootTaskKind: PLAN
  maxPlanCorrections: 2
  defaultExecutionMode: SEQUENTIAL
  allowedSubTaskRecipes:
    - {{ params.gathererRecipe }}
promptPrefix: |
  You are the {{ params.name }} PLAN node.{% if params.processGoalLabel %} The {{ params.processGoalLabel }} comes from the process goal.{% endif %}

  Emit EXACTLY {{ params.aspects | length + 1 }} children in this order. Use ONLY taskKind WORKER and AGGREGATE; no other kinds.

{%- for aspect in params.aspects %}

  KIND {{ loop.index + 1 }} — WORKER {{ params.gathererRecipe }} ({{ aspect.role }}):
  {"taskKind":"WORKER",
   "goal":"{% verbatim %}{{ process.goal }}{% endverbatim %} — {{ aspect.goal }}",
   "taskSpec":{"recipe":"{{ params.gathererRecipe }}"}}
{%- endfor %}

  KIND {{ params.aspects | length + 1 }} — AGGREGATE (synthesizes the final result and writes it):
  {"taskKind":"AGGREGATE",
   "goal":"Synthesize the prior siblings into the final deliverable.",
   "taskSpec":{
     "prompt":"{{ params.synthesisPrompt | replace({'\"':'\\\"'}) }} Language: {{ params.language }}.{% if params.reportLengthWords %} Length: {{ params.reportLengthWords }} words.{% endif %}",
     "maxOutputChars":{{ params.maxOutputChars | default(15000) }},
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"{{ params.outputPathTpl }}",
          "content":"{% verbatim %}{{ node.summary }}{% endverbatim %}"{% if params.outputTitleTpl %},
          "title":"{{ params.outputTitleTpl }}"{% endif %}}}
     ]}}

  Output contract — EXACTLY {{ params.aspects | length + 1 }} children:
      {"children":[{% for i in 1..(params.aspects | length) %}<KIND {{ i }}>,{% endfor %}<KIND {{ params.aspects | length + 1 }}>]}

  Do not omit any KIND. The number of children MUST be {{ params.aspects | length + 1 }}.
