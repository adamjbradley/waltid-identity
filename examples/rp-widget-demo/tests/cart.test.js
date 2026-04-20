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
});
