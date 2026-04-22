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
        ageRestricted: product.ageRestricted,
        minAge: product.minAge
    });
    cart.updatedAt = Date.now();
    return cart;
}

/**
 * Set the quantity of `productId` to `qty`. Missing lines are a no-op —
 * callers decide whether that's a 404 or a silent skip. `qty <= 0` drops
 * the line entirely so PATCH can double as a soft-delete.
 */
function setQty(cart, productId, qty) {
    const item = cart.items.find(i => i.productId === productId);
    if (!item) return cart;
    if (qty <= 0) cart.items = cart.items.filter(i => i.productId !== productId);
    else item.qty = qty;
    cart.updatedAt = Date.now();
    return cart;
}

function removeItem(cart, productId) {
    cart.items = cart.items.filter(i => i.productId !== productId);
    cart.updatedAt = Date.now();
    return cart;
}

function clearCart(cart) {
    cart.items = [];
    cart.updatedAt = Date.now();
    return cart;
}

module.exports = { emptyCart, summary, addItem, setQty, removeItem, clearCart };
