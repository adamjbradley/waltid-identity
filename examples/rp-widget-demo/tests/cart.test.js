const request = require('supertest');
const { createApp } = require('../server');

describe('cart API', () => {
  let app, agent;
  beforeEach(() => { app = createApp(); agent = request.agent(app); });

  it('GET /api/cart on fresh session returns empty', async () => {
    const res = await agent.get('/api/cart').expect(200);
    expect(res.body).toEqual({ items: [], subtotal: 0, count: 0 });
  });
});
