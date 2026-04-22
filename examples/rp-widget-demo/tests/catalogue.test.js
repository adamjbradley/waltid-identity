const { CATALOGUE, getProduct } = require('../catalogue');

describe('catalogue', () => {
  it('has 12 products', () => {
    expect(CATALOGUE).toHaveLength(12);
  });
  it('every product has id, name, priceSingle, minAge, ageRestricted', () => {
    for (const p of CATALOGUE) {
      expect(p).toMatchObject({
        id: expect.any(String),
        name: expect.any(String),
        priceSingle: expect.any(Number),
        minAge: expect.any(Number),
        ageRestricted: true,
      });
    }
  });
  it('getProduct returns the entry by id', () => {
    expect(getProduct('hibiki-harmony')).toBeTruthy();
    expect(getProduct('hibiki-harmony').name).toMatch(/Hibiki/);
  });
  it('getProduct returns null for unknown id', () => {
    expect(getProduct('does-not-exist')).toBeNull();
  });
});
