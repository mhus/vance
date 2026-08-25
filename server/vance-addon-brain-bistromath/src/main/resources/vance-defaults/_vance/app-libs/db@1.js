// db@1 — a folder of documents as a table.
//
// @require core@1
//
// `core.rows/save/remove` are the primitives: one read per document, one write
// per record, no memory of what was read. This is the abstraction over them —
// a table object that holds its rows, answers questions without going back to
// the server, and knows what to call a new record.
//
// An app that shows no data never loads this, which is the point of it being a
// separate library rather than more of `core@1`.
//
// One convention, and it is `core`'s: a record's `key` is its file name without
// the extension, and it is not stored inside the document.

const db = {

  /**
   * A handle on one folder.
   *
   * Nothing is read until the first call that needs rows. So declaring a table
   * the current view does not show costs nothing.
   *
   * Options:
   *   extension  file extension for new records, default `.yaml`
   *   keyPrefix  prefix for generated keys, default none
   *   keyWidth   digits a generated key is padded to, default from the folder
   */
  table: function (folder, options) {
    const opts = options || {};
    db.checkFolder(folder);
    const extension = opts.extension || '.yaml';
    const keyPrefix = opts.keyPrefix || '';
    let rows = null;

    /**
     * Rows, read once and kept.
     *
     * <p>The cache is the reason this exists: `where` and `count` and a paging
     * table would each re-read the whole folder otherwise, and that is one HTTP
     * call per document per question. Mutations keep it in step; `reload()` is
     * for a change somebody else made.
     */
    async function all() {
      if (rows === null) rows = await core.rows(folder);
      return rows;
    }

    function requireLoaded() {
      if (rows === null) {
        throw new Error('db: call `await table.all()` (or any read) before this.');
      }
      return rows;
    }

    /** A match object becomes a predicate; a predicate stays one. */
    function toPredicate(match) {
      if (typeof match === 'function') return match;
      const keys = Object.keys(match || {});
      return function (row) {
        for (const k of keys) {
          if (row[k] !== match[k]) return false;
        }
        return true;
      };
    }

    const table = {

      folder: folder,

      all: all,

      /** Drop the cache and read again. */
      reload: async function () {
        rows = null;
        return all();
      },

      /** One record by key, or `null`. Absence is an answer, not an error. */
      get: async function (key) {
        const list = await all();
        for (const row of list) {
          if (row.key === key) return row;
        }
        return null;
      },

      /** `table.where({status: 'open'})` or `table.where(r => r.amount > 100)` */
      where: async function (match) {
        return (await all()).filter(toPredicate(match));
      },

      /** The first match, or `null`. */
      first: async function (match) {
        const hit = await table.where(match);
        return hit.length ? hit[0] : null;
      },

      count: async function (match) {
        return match === undefined
          ? (await all()).length
          : (await table.where(match)).length;
      },

      /**
       * Write a new record. Its `key` is generated when absent.
       *
       * Refuses an existing key rather than overwriting: "add" and "replace"
       * are different intentions, and a silent overwrite here is lost data with
       * no trace. Use `upsert` when either is fine.
       */
      insert: async function (record) {
        const list = await all();
        const rec = Object.assign({}, record);
        if (!rec.key) rec.key = table.nextKey(list);
        for (const row of list) {
          if (row.key === rec.key) {
            throw new Error("db: '" + rec.key + "' already exists in " + folder
              + '. Use upsert() to replace it.');
          }
        }
        await core.save(folder, rec, extension);
        list.push(rec);
        return rec;
      },

      /**
       * Merge a patch into an existing record.
       *
       * Throws when the key is unknown — an update that silently creates is how
       * a typo in a key becomes a second, nearly identical record.
       */
      update: async function (key, patch) {
        const list = await all();
        const i = list.findIndex(function (r) { return r.key === key; });
        if (i < 0) {
          throw new Error("db: no record '" + key + "' in " + folder + '.');
        }
        const merged = Object.assign({}, list[i], patch, { key: key });
        await core.save(folder, merged, extension);
        list[i] = merged;
        return merged;
      },

      /** Write, whether or not it is there. Replaces the whole record. */
      upsert: async function (record) {
        const list = await all();
        const rec = Object.assign({}, record);
        if (!rec.key) rec.key = table.nextKey(list);
        await core.save(folder, rec, extension);
        const i = list.findIndex(function (r) { return r.key === rec.key; });
        if (i < 0) list.push(rec); else list[i] = rec;
        return rec;
      },

      remove: async function (key) {
        const list = await all();
        await core.remove(folder, key, extension);
        const i = list.findIndex(function (r) { return r.key === key; });
        if (i >= 0) list.splice(i, 1);
      },

      /**
       * A key no record in the folder has: the highest numeric one plus one,
       * zero-padded to the width already in use.
       *
       * <p>The question "what do I call a new record" has to be answered
       * somewhere, and every app answering it differently is how a folder ends
       * up with `1`, `002` and `rec-3` side by side. Counting rows would be
       * wrong: delete the last one and the next insert collides.
       *
       * <p>Synchronous, and it takes the rows — so it can be used inside a loop
       * that also inserts, without a read per iteration.
       */
      nextKey: function (list) {
        const pattern = new RegExp('^' + keyPrefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
          + '(\\d+)$');
        let max = 0;
        let width = opts.keyWidth || 0;
        for (const row of list || requireLoaded()) {
          const m = pattern.exec(String(row.key));
          if (!m) continue;
          const n = parseInt(m[1], 10);
          if (n > max) max = n;
          if (!opts.keyWidth && m[1].length > width) width = m[1].length;
        }
        const next = String(max + 1);
        return keyPrefix + (next.length >= (width || 1)
          ? next
          : '0'.repeat(width - next.length) + next);
      },
    };

    return table;
  },

  /**
   * Refuse a folder that repeats the app's own folder.
   *
   * <p>`vance.documents.*` resolves against the app folder, so
   * `db.table(vance.app.folder + '/records/')` — a very natural line to write —
   * addresses `apps/mine/apps/mine/records/`. It **works**: writes and reads
   * double identically, the table behaves, and the records sit somewhere nobody
   * looks. Nothing fails, which is what makes it worth a check.
   *
   * <p>Refused rather than silently corrected, for the same reason a REST path
   * above the tenant root is refused: the author expected a different grammar,
   * and quietly reinterpreting the argument would leave that belief in place.
   */
  checkFolder: function (folder) {
    const app = (typeof vance !== 'undefined' && vance.app && vance.app.folder) || '';
    const f = String(folder == null ? '' : folder);
    if (!f) throw new Error('db.table needs a folder.');
    if (!app || f.charAt(0) === '/') return;
    const bare = f.replace(/^\.\//, '');
    if (bare === app || bare.indexOf(app + '/') === 0) {
      throw new Error("db.table('" + f + "') repeats the app folder. Paths are relative to '"
        + app + "' -- use '" + bare.slice(app.length).replace(/^\//, '')
        + "', or a leading slash for a path from the project root.");
    }
  },
};
