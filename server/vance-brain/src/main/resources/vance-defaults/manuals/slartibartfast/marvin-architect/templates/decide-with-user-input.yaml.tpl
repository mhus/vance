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
promptPrefix: |
  You are the {{ params.name }} PLAN node.{% if params.processGoalLabel %} The {{ params.processGoalLabel }} comes from the process goal.{% endif %}

  Emit EXACTLY {{ params.questions | length + 1 }} children in this order. Use only USER_INPUT and WORKER; no other kinds.

{%- for q in params.questions %}

  KIND {{ loop.index + 1 }} — USER_INPUT ({{ q.role | default('clarification') }}):
  {"taskKind":"USER_INPUT",
   "taskSpec":{
     "type":"{{ q.type | default('DECISION') }}",
     "criticality":"{{ q.criticality | default('NORMAL') }}",
     "title":"{{ q.title | replace({'\"':'\\\"'}) }}",
     "body":"{{ q.body | replace({'\"':'\\\"'}) }}"{% if q.options is not null and q.options is not empty %},
     "payload":{"options":{{ q.options }}}{% endif %}}}
{%- endfor %}

  KIND {{ params.questions | length + 1 }} — WORKER marvin-worker (synthesizes the decision and writes it):
  {"taskKind":"WORKER",
   "goal":"{{ params.decisionPrompt | replace({'\"':'\\\"'}) }}",
   "taskSpec":{
     "recipe":"marvin-worker",
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"{{ params.outputPathTpl }}",
          "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"{% if params.outputTitleTpl %},
          "title":"{{ params.outputTitleTpl }}"{% endif %}}}
     ]}}

  Output contract — EXACTLY {{ params.questions | length + 1 }} children:
      {"children":[{% for i in 1..(params.questions | length) %}<KIND {{ i }}>,{% endfor %}<KIND {{ params.questions | length + 1 }}>]}

  Do not omit any KIND. The number of children MUST be {{ params.questions | length + 1 }}.
