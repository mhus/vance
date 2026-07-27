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
  You are to make a decision, but first you need input from the
  user.

  Approach:
  - In one or more NEEDS_USER_INPUT turns, ask the user the
    following questions:
{%- for q in params.questions %}
    - {{ q.title }} ({{ q.type | default('FEEDBACK') }}): {{ q.body | yamlIndent(6) }}{% if q.options is not null and q.options is not empty %}
      Options: {{ q.options }}{% endif %}
{%- endfor %}
  - You may also ask the questions sequentially (one after the
    other via NEEDS_USER_INPUT in separate SCOPE/REFLECT turns)
    or all at once via NEEDS_SUBTASKS with USER_INPUT children —
    depending on whether a later question depends on the answer
    to an earlier one.
  - Once all answers are in: {{ params.decisionPrompt | yamlIndent(4) }}.
  - Language: {{ params.language }}.

  When your decision is ready (in CONCLUDE), additionally return
  the following postActions so that the result is persisted:
    [
      {"tool":"doc_write",
       "args":{
         "path":"{{ params.outputPathTpl }}",
         "kind":"text",
         "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"{% if params.outputTitleTpl %},
         "title":"{{ params.outputTitleTpl }}"{% endif %}}}
    ]
