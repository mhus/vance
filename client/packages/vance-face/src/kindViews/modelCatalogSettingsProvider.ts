import { brainFetch } from '@vance/shared';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentSearchResponse,
} from '@vance/generated';
import type { SettingsDocRow, SettingsProvider, SettingsScope } from '@vance/kind-registry';
import { MODEL_SOURCE_KIND } from './modelDocCodec';
import { PROVIDER_SOURCE_KIND } from './providerDocCodec';

/**
 * Settings contribution of the model-catalog kinds: the operator-managed
 * model + provider documents under {@code _vance/model/} (one YAML per model,
 * one {@code _provider.yaml} sidecar per provider instance — see
 * {@code ModelCatalog.MODEL_PATH_PREFIX}). The Settings page's "Bereiche"
 * tab shows this as the AI-models area: inventory, add, delete; an entry
 * opens in its kind's own form view right there (inline), not as a Cortex
 * link.
 *
 * <p><b>The inventory is location-based, like the catalog itself.</b> The
 * catalog scans the path prefix and deep-merges whatever it finds — kind
 * markers are not part of its contract — so the listing uses the recursive
 * document search scoped to {@code _vance/model/} instead of a kind filter,
 * and assigns each row its view kind by filename ({@code _provider.yaml} ⇒
 * provider sidecar, everything else ⇒ model). This is deliberately different
 * from the three source-config areas, whose server side drops untyped
 * documents and therefore needs the marker.
 *
 * <p><b>Create names carry structure:</b> {@code provider} seeds a provider
 * sidecar; {@code provider/model} seeds a model document in that provider.
 * The wire-name encoding follows the catalog: a {@code /} inside the model
 * name nests directories (HF-style names), a {@code :} moves the name into
 * an explicit {@code wireName} field with a filesystem-safe filename slug
 * (Ollama tags).
 *
 * <p>Served for the tenant and project layers only: the catalog cascade
 * (classpath → system tenant → {@code _tenant} → project) has no user layer.
 */
export const modelCatalogSettingsProvider: SettingsProvider = {
  titleKey: 'settings.areas.models.title',
  createHintKey: 'settings.areas.models.createHint',
  list: async (scope: SettingsScope): Promise<SettingsDocRow[]> => {
    if (scope.kind === 'user') return [];
    return listModelDocs(scope.projectId);
  },
  create: async (scope: SettingsScope, name: string): Promise<SettingsDocRow> => {
    if (scope.kind === 'user') {
      throw new Error('Model documents are not available in the user scope.');
    }
    const trimmed = name.trim();
    if (!trimmed) throw new Error('A name is required.');

    const slash = trimmed.indexOf('/');
    const body: DocumentCreateRequest = slash < 0
      ? providerSeed(trimmed)
      : modelSeed(trimmed.substring(0, slash), trimmed.substring(slash + 1));

    const params = new URLSearchParams({ projectId: scope.projectId });
    const doc = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params.toString()}`,
      { body },
    );
    return rowOf(scope.projectId, doc.id, body.path!, body.path!);
  },
};

const CATALOG_PREFIX = '_vance/model/';

function providerSeed(provider: string): DocumentCreateRequest {
  const path = `${CATALOG_PREFIX}${provider}/_provider.yaml`;
  return {
    path,
    inlineText:
      '# New provider instance — adjust wireType/authType/baseUrl.\n'
      + `$meta:\n  kind: ${PROVIDER_SOURCE_KIND}\n`
      + `displayName: ${provider}\nwireType: openai\nauthType: api-key\n`,
  };
}

function modelSeed(provider: string, model: string): DocumentCreateRequest {
  // Wire-name encoding, mirroring ModelCatalog: ':' cannot live in a
  // filename — the name moves into an explicit wireName field and the
  // file carries a slug. '/' stays directory structure (HF-style names).
  const needsExplicitWireName = model.includes(':');
  const fileStem = needsExplicitWireName ? model.replace(/:/g, '-') : model;
  const path = `${CATALOG_PREFIX}${provider}/${fileStem}.yaml`;
  const wireNameLine = needsExplicitWireName ? `wireName: "${model}"\n` : '';
  const display = model.includes('/')
    ? model.substring(model.lastIndexOf('/') + 1)
    : model;
  return {
    path,
    inlineText:
      // Partial is the point: this document overrides what it says and
      // deep-merges with the outer layers for everything else.
      '# New model document — partial is fine, everything unset inherits.\n'
      + `$meta:\n  kind: ${MODEL_SOURCE_KIND}\n`
      + `displayName: ${display}\n${wireNameLine}`
      + 'contextWindowTokens: 8192\ndefaultMaxOutputTokens: 4096\nsize: SMALL\n',
  };
}

async function listModelDocs(projectId: string): Promise<SettingsDocRow[]> {
  const params = new URLSearchParams({
    projectId,
    pathPrefix: CATALOG_PREFIX,
    size: '200',
  });
  const data = await brainFetch<DocumentSearchResponse>(
    'GET',
    `documents/search?${params.toString()}`,
  );
  return data.items.map((item) => rowOf(projectId, item.id, item.path, item.title ?? item.path));
}

function rowOf(projectId: string, documentId: string, path: string, title: string): SettingsDocRow {
  const providerSidecar = path.endsWith('/_provider.yaml');
  const rel = path.substring(CATALOG_PREFIX.length).replace(/\.ya?ml$/, '');
  const name = providerSidecar ? rel.substring(0, rel.lastIndexOf('/')) : rel;
  return {
    name,
    title,
    kindId: providerSidecar ? PROVIDER_SOURCE_KIND : MODEL_SOURCE_KIND,
    documentId,
    projectId,
    path,
  };
}
