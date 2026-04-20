'use strict';

/**
 * Session-backed shopping cart helpers.
 *
 * The cart lives entirely in the express-session store — there's no database
 * layer yet. Each helper takes the plain `{ items, updatedAt }` object (or a
 * product from catalogue.js) and mutates it in place, returning the same
 * reference so callers can chain.
 *
 * `summary(cart)` shapes the session-side data for the wire: it strips the
 * `updatedAt` timestamp and adds `subtotal` / `count` aggregates so the
 * client never needs to know about the internal bookkeeping fields.
 */

function emptyCart() {
    return { items: [], updatedAt: Date.now() };
}

function summary(cart) {
    const count = cart.items.reduce((n, i) => n + i.qty, 0);
    const subtotal = Math.round(cart.items.reduce((n, i) => n + i.qty * i.priceAud, 0) * 100) / 100;
    return { items: cart.items, subtotal, count };
}

/**
 * Add `qty` of `product` to `cart`. If the line already exists we bump its
 * quantity; otherwise we push a new line using the product's single-unit
 * price so the on-cart copy survives later catalogue edits.
 */
function addItem(cart, product, qty = 1) {
    const existing = cart.items.find(i => i.productId === product.id);
    if (existing) existing.qty += qty;
    else cart.items.push({
        productId: product.id,
        qty,
        priceAud: product.priceSingle,
        title: product.name,
        imageUrl: product.icon,
        ageRestricted: product.ageRestricted
    });
    cart.updatedAt = Date.now();
    return cart;
}

module.exports = { emptyCart, summary, addItem };
