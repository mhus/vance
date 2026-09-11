// @vitest-environment jsdom
//
// The component's whole contract is the fallback switch: tenant logo
// while the image loads, bundled VanceLogo when there is no URL (no
// session) or the load fails (typically the endpoint's 404 for a
// tenant without a logo). `tenantLogoUrl` is mocked so no request is
// made and each case is decided by the seam, not the network.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const tenantLogoUrl = vi.fn();

vi.mock('@vance/shared', () => ({
  tenantLogoUrl: () => tenantLogoUrl(),
}));

import TenantLogo from './TenantLogo.vue';

describe('TenantLogo', () => {
  beforeEach(() => {
    tenantLogoUrl.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the bundled VanceLogo when there is no session yet', () => {
    tenantLogoUrl.mockReturnValue('');

    const wrapper = mount(TenantLogo);

    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('svg').exists()).toBe(true);
  });

  it('renders the tenant logo with the pinned topbar size', () => {
    tenantLogoUrl.mockReturnValue('/brain/acme/ui/logo');

    const wrapper = mount(TenantLogo);

    const img = wrapper.find('img');
    expect(img.attributes('src')).toBe('/brain/acme/ui/logo');
    expect(img.classes()).toEqual(expect.arrayContaining(['h-5', 'w-5', 'object-contain']));
    expect(wrapper.find('svg').exists()).toBe(false);
  });

  it('falls back to the VanceLogo when the image fails to load', async () => {
    // The one production path this guards: the endpoint answers 404 for
    // a tenant without a logo and the browser fires `error` on the img.
    tenantLogoUrl.mockReturnValue('/brain/acme/ui/logo');

    const wrapper = mount(TenantLogo);
    expect(wrapper.find('img').exists()).toBe(true);

    await wrapper.find('img').trigger('error');

    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('svg').exists()).toBe(true);
  });
});
