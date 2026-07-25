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
  You are to carry out research on the topic {% verbatim %}{{ process.goal }}{% endverbatim %} and produce a
  coherent report.

  Approach:
  - Examine the topic along the following aspects:
{%- for a in params.aspects %}
    - {{ a.role }}: {{ a.goal | yamlIndent(6) }}
{%- endfor %}
  - Use the tools listed in availableRecipes via CALL_RECIPE
    (e.g. {% for r in params.availableRecipes %}{% if not loop.first %}, {% endif %}{{ r }}{% endfor %}) to gather material.
    You may work through the aspects sequentially or split them
    into parallel children via NEEDS_SUBTASKS — whichever makes
    more sense.
  - {{ params.synthesisPrompt | yamlIndent(4) }}{% if params.reportLengthWords %} Target length: {{ params.reportLengthWords }} words.{% endif %}
  - Language: {{ params.language }}.

  When your report is ready (in CONCLUDE), additionally return
  the following postActions in the JSON so that the report is
  persisted:
    [
      {"tool":"doc_create",
       "args":{
         "path":"{{ params.outputPathTpl }}",
         "kind":"text",
         "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"{% if params.outputTitleTpl %},
         "title":"{{ params.outputTitleTpl }}"{% endif %}}}
    ]
