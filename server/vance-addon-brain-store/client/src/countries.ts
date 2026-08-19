/**
 * The countries a store sells to, for the forms that have to ask.
 *
 * <p>One list, because two of them drift: a kit purchase and a publishing
 * renewal are both sales the store has to tax, and a country offered by one
 * form and not the other is a sale somebody cannot make for no reason they
 * can see.
 *
 * <p>It mirrors `SalesCountryPolicy`'s default on the server, which stays
 * the authority — a store that widens its list refuses nothing here, it just
 * does not offer the new one until this is widened too. The server refusing
 * is the check; this is the convenience.
 */
export const EU_COUNTRIES = [
  'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU',
  'IE', 'IT', 'LV', 'LT', 'LU', 'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE',
];

/** The same list in the shape `VSelect` takes. */
export const COUNTRY_OPTIONS = EU_COUNTRIES.map((code) => ({ value: code, label: code }));
