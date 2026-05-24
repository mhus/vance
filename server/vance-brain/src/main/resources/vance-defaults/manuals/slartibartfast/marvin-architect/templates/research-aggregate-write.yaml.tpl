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
  Du sollst eine Recherche zum Thema {% verbatim %}{{ process.goal }}{% endverbatim %} durchführen und einen
  zusammenhängenden Bericht erstellen.

  Vorgehen:
  - Beleuchte das Thema entlang folgender Aspekte:
{%- for a in params.aspects %}
    - {{ a.role }}: {{ a.goal | yamlIndent(6) }}
{%- endfor %}
  - Nutze die in availableRecipes aufgeführten Werkzeuge via
    CALL_RECIPE (z.B. {% for r in params.availableRecipes %}{% if not loop.first %}, {% endif %}{{ r }}{% endfor %}), um Material zu sammeln.
    Du darfst die Aspekte sequenziell abarbeiten oder via
    NEEDS_SUBTASKS in parallele Kinder aufteilen — was sinnvoller
    ist.
  - {{ params.synthesisPrompt | yamlIndent(4) }}{% if params.reportLengthWords %} Ziellänge: {{ params.reportLengthWords }} Wörter.{% endif %}
  - Sprache: {{ params.language }}.

  Wenn dein Bericht fertig ist (in CONCLUDE), gib zusätzlich
  folgende postActions im JSON zurück, damit der Bericht
  persistiert wird:
    [
      {"tool":"doc_write_text",
       "args":{
         "path":"{{ params.outputPathTpl }}",
         "content":"{% verbatim %}{{ node.result }}{% endverbatim %}"{% if params.outputTitleTpl %},
         "title":"{{ params.outputTitleTpl }}"{% endif %}}}
    ]
