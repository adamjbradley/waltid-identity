const request = require('supertest');
const { createApp } = require('../server');

describe('cart API', () => {
  let app, agent;
  beforeEach(() => { app = createApp(); agent = request.agent(app); });

  it('GET /api/cart on fresh session returns empty', async () => {
    const res = await agent.get('/api/cart').expect(200);
    expect(res.body).toEqual({ items: [], subtotal: 0, count: 0 });
  });

  it('POST /api/cart/items without age verification returns 403 for 21+ product', async () => {
    const res = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(403);
    expect(res.body).toEqual({ error: 'age_verification_required', minAge: 21 });
  });

  it('POST /api/cart/items with session.ageVerified adds item', async () => {
    await agent.post('/_test/session').send({ ageVerified: true }).expect(200);
    const res = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    expect(res.body.count).toBe(1);
    expect(res.body.items[0]).toMatchObject({ productId: 'hibiki-harmony', qty: 1, priceAud: 89.00 });
  });

  it('POST /api/cart/items with logged-in user age_over_21 adds item', async () => {
    await agent.post('/_test/session').send({ user: { sub: 'u', age_over_21: true } }).expect(200);
    const res = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    expect(res.body.count).toBe(1);
  });

  it('POST /api/cart/items returns 404 for unknown product', async () => {
    await agent.post('/_test/session').send({ ageVerified: true }).expect(200);
    const res = await agent.post('/api/cart/items').send({ productId: 'nope' }).expect(404);
    expect(res.body).toEqual({ error: 'unknown_product' });
  });

  it('PATCH /api/cart/items/:id changes qty', async () => {
    await agent.post('/_test/session').send({ ageVerified: true });
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' });
    const res = await agent.patch('/api/cart/items/hibiki-harmony').send({ qty: 3 }).expect(200);
    expect(res.body.count).toBe(3);
  });

  it('PATCH with qty=0 removes the item', async () => {
    await agent.post('/_test/session').send({ ageVerified: true });
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' });
    const res = await agent.patch('/api/cart/items/hibiki-harmony').send({ qty: 0 }).expect(200);
    expect(res.body.count).toBe(0);
    expect(res.body.items).toEqual([]);
  });

  it('PATCH 403 when increasing qty on age-restricted item without age verification', async () => {
    // seed an item via hydration (bypassing the POST gate so we can test PATCH gate)
    await agent.post('/_test/session').send({ ageVerified: true });
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' });
    // now drop ageVerified and try to increase qty
    await agent.post('/_test/session').send({ ageVerified: false });
    const res = await agent.patch('/api/cart/items/hibiki-harmony').send({ qty: 5 }).expect(403);
    expect(res.body).toEqual({ error: 'age_verification_required', minAge: 21 });
  });

  it('DELETE removes the item', async () => {
    await agent.post('/_test/session').send({ ageVerified: true });
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' });
    const res = await agent.delete('/api/cart/items/hibiki-harmony').expect(200);
    expect(res.body.count).toBe(0);
  });

  it('POST /api/cart/clear empties the cart', async () => {
    await agent.post('/_test/session').send({ ageVerified: true });
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' });
    await agent.post('/api/cart/items').send({ productId: 'yamazaki-12' });
    const res = await agent.post('/api/cart/clear').expect(200);
    expect(res.body.count).toBe(0);
    expect(res.body.items).toEqual([]);
  });
});
