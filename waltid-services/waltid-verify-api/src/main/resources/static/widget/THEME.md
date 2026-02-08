# WaltVerify Widget Theme System

The WaltVerify Widget SDK supports full theme customization through either CSS utility classes (Tailwind-compatible) or CSS custom properties (variables) for non-Tailwind projects.

## Quick Start

### Using Tailwind CSS

```html
<script src="https://your-domain.com/widget/sdk.js"></script>
<script src="https://your-domain.com/widget/themes.js"></script>
<script>
  WaltVerify.init({
    clientToken: 'ct_your_token',
    classTheme: WaltVerifyThemes.tailwind
  });
</script>
```

### Using Theme Presets

```javascript
// Light theme (default)
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.presets.light
});

// Dark theme
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.presets.dark
});

// Enterprise theme
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.presets.enterprise
});

// Minimal theme
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.presets.minimal
});
```

### Using CSS Variables (Non-Tailwind)

```html
<script src="https://your-domain.com/widget/sdk.js"></script>
<script src="https://your-domain.com/widget/themes.js"></script>
<script>
  // Inject default CSS variable styles
  WaltVerifyThemes.injectCSSVariableStyles({
    primary: '#2563eb',
    primaryHover: '#1d4ed8',
    success: '#10b981',
    error: '#ef4444'
  });

  WaltVerify.init({
    clientToken: 'ct_your_token',
    classTheme: WaltVerifyThemes.cssVariables
  });
</script>
```

## Theme Interface

The `classTheme` option accepts an object with the following properties:

```typescript
interface WidgetTheme {
  // Modal structure
  overlay: string;        // Modal backdrop (fixed, centered, z-index)
  modal: string;          // Modal container (background, rounded, shadow)
  header: string;         // Header container (padding, border)
  content: string;        // Content area (padding, text-align)
  footer: string;         // Footer container (padding, border)

  // Typography
  title: string;          // Modal title (font-size, font-weight, color)
  subtitle: string;       // Instruction text (font-size, color, margin)
  footerText: string;     // Footer text (font-size, color)
  statusText: string;     // Status indicator text (font-size, color)

  // QR Code
  qrContainer: string;    // QR code wrapper (background, padding, rounded)

  // Buttons
  primaryButton: string;  // Primary action button (bg, text, padding, rounded)
  secondaryButton: string;// Cancel/secondary button (text, cursor)
  closeButton: string;    // Modal close button (padding, cursor)
  deepLinkButton: string; // "Open Wallet" button (same as primaryButton)

  // Divider
  divider: string;        // "or" divider container (flex, align)
  dividerLine: string;    // Divider horizontal line (height, bg)
  dividerText: string;    // Divider "or" text (padding, font-size)

  // Status indicators
  pendingStatus: string;  // Waiting status text (color)
  successStatus: string;  // Success status text (color, font-weight)
  errorStatus: string;    // Error status text (color, font-weight)

  // Icons
  successIcon: string;    // Success checkmark wrapper (size, bg, rounded)
  errorIcon: string;      // Error X wrapper (size, bg, rounded)

  // Loading
  spinner: string;        // Loading spinner (size, border, animate)

  // Inline mode
  inlineContainer: string;// Container for inline UI mode

  // Result display
  resultSummary: string;  // Verification result summary (margin, padding, bg)
}
```

## Creating Custom Themes

### Extend an Existing Theme

```javascript
const myTheme = WaltVerifyThemes.extend(WaltVerifyThemes.tailwind, {
  primaryButton: 'w-full bg-purple-600 hover:bg-purple-700 text-white py-3 px-6 rounded-full',
  successIcon: 'w-20 h-20 bg-purple-500 rounded-full flex items-center justify-center mx-auto mb-5'
});

WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: myTheme
});
```

### Create from Color Palette

```javascript
const brandTheme = WaltVerifyThemes.fromPalette({
  primary: 'indigo-600',
  primaryHover: 'indigo-700',
  background: 'white',
  text: 'gray-900',
  textMuted: 'gray-500',
  border: 'gray-200',
  success: 'emerald-500',
  error: 'rose-500'
});

WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: brandTheme
});
```

### Build Theme from Scratch

```javascript
const customTheme = {
  overlay: 'fixed inset-0 bg-black/60 flex items-center justify-center z-50',
  modal: 'bg-slate-900 rounded-2xl shadow-2xl max-w-sm w-11/12 max-h-screen overflow-auto',
  header: 'px-6 py-4 border-b border-slate-700 flex items-center justify-between',
  content: 'p-6 text-center',
  footer: 'px-6 py-3 border-t border-slate-700 text-center',
  title: 'text-xl font-bold text-white',
  subtitle: 'text-slate-300 mb-6',
  footerText: 'text-xs text-slate-500',
  statusText: 'text-sm text-slate-400',
  qrContainer: 'bg-white p-4 rounded-xl inline-block mb-5',
  primaryButton: 'w-full bg-cyan-500 hover:bg-cyan-400 text-slate-900 py-3 px-6 rounded-xl font-semibold',
  secondaryButton: 'text-slate-400 hover:text-slate-200 text-sm mt-3',
  closeButton: 'text-slate-500 hover:text-slate-300 text-2xl p-2',
  deepLinkButton: 'w-full bg-cyan-500 hover:bg-cyan-400 text-slate-900 py-3 px-6 rounded-xl font-semibold no-underline',
  divider: 'flex items-center my-5 text-slate-500',
  dividerLine: 'flex-1 h-px bg-slate-700',
  dividerText: 'px-3 text-sm',
  pendingStatus: 'text-sm text-slate-400',
  successStatus: 'text-sm text-emerald-400 font-medium',
  errorStatus: 'text-sm text-rose-400 font-medium',
  successIcon: 'w-16 h-16 bg-emerald-500 rounded-full flex items-center justify-center mx-auto mb-5',
  errorIcon: 'w-16 h-16 bg-rose-500 rounded-full flex items-center justify-center mx-auto mb-5',
  spinner: 'w-6 h-6 border-2 border-slate-600 border-t-cyan-500 rounded-full animate-spin mx-auto mb-4',
  inlineContainer: 'bg-slate-900 rounded-2xl border border-slate-700 p-6 text-center',
  resultSummary: 'mt-4 p-3 bg-slate-800 rounded-lg text-left'
};

WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: customTheme
});
```

## Dynamic Theme Switching

You can change themes at runtime using the `setTheme` method:

```javascript
// Initialize with light theme
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.presets.light
});

// Later, switch to dark theme
document.querySelector('#dark-mode-toggle').addEventListener('click', () => {
  WaltVerify.setTheme(WaltVerifyThemes.presets.dark);
});

// Get current theme
const currentTheme = WaltVerify.getTheme();

// Get default theme (useful for extending)
const defaults = WaltVerify.getDefaultTheme();
```

## CSS Variables for Non-Tailwind Projects

If you're not using Tailwind CSS, you can use the CSS Variables theme with your own stylesheet:

### Option 1: Inject Styles Automatically

```javascript
WaltVerifyThemes.injectCSSVariableStyles({
  primary: '#3b82f6',
  primaryHover: '#2563eb',
  background: '#ffffff',
  text: '#1f2937',
  textMuted: '#6b7280',
  border: '#e5e7eb',
  success: '#10b981',
  error: '#ef4444',
  radius: '16px'
});

WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.cssVariables
});
```

### Option 2: Include CSS Manually

Get the CSS content to include in your build:

```javascript
const css = WaltVerifyThemes.getCSSVariableStyles({
  primary: '#your-brand-color'
});
console.log(css); // Copy and paste into your stylesheet
```

Or add this to your stylesheet:

```css
:root {
  --wv-primary: #2563eb;
  --wv-primary-hover: #1d4ed8;
  --wv-background: #ffffff;
  --wv-background-alt: #f9fafb;
  --wv-text: #1f2937;
  --wv-text-muted: #6b7280;
  --wv-text-light: #9ca3af;
  --wv-border: #e5e7eb;
  --wv-success: #10b981;
  --wv-error: #ef4444;
  --wv-overlay: rgba(0, 0, 0, 0.5);
  --wv-radius: 12px;
  --wv-radius-sm: 8px;
  --wv-font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  --wv-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
}
```

Then use the CSS Variables theme:

```javascript
WaltVerify.init({
  clientToken: 'ct_xxx',
  classTheme: WaltVerifyThemes.cssVariables
});
```

## Available Presets

| Preset | Description |
|--------|-------------|
| `light` | Default light theme - clean and professional |
| `dark` | Dark mode theme with gray-900 background |
| `minimal` | Subtle, unobtrusive design with reduced padding |
| `enterprise` | Professional corporate look with larger sizing |

## Legacy Theme Support

The SDK still supports the legacy inline-style theme for backward compatibility:

```javascript
WaltVerify.init({
  clientToken: 'ct_xxx',
  theme: {
    primaryColor: '#2563eb',
    backgroundColor: '#ffffff',
    textColor: '#1f2937',
    borderRadius: '12px',
    fontFamily: 'Inter, sans-serif'
  }
});
```

Note: If both `theme` and `classTheme` are provided, `classTheme` takes precedence.

## Required Tailwind Configuration

If using the default Tailwind theme, ensure these utilities are available:

```javascript
// tailwind.config.js
module.exports = {
  content: [
    // Include the widget SDK in content scanning
    './node_modules/@waltid/verify-widget/**/*.js',
    // Or safelist required classes
  ],
  theme: {
    extend: {
      animation: {
        spin: 'spin 1s linear infinite',
      },
    },
  },
};
```

Or safelist the classes:

```javascript
// tailwind.config.js
module.exports = {
  safelist: [
    'fixed', 'inset-0', 'flex', 'items-center', 'justify-center',
    'bg-white', 'bg-black/50', 'rounded-xl', 'shadow-2xl',
    'max-w-md', 'w-[90%]', 'max-h-[90vh]', 'overflow-auto',
    'px-6', 'py-5', 'py-3', 'py-4', 'p-6', 'p-4',
    'border-b', 'border-t', 'border', 'border-gray-200', 'border-gray-100',
    'text-lg', 'text-base', 'text-sm', 'text-xs', 'text-2xl',
    'font-semibold', 'font-medium',
    'text-gray-900', 'text-gray-500', 'text-gray-400',
    'text-green-600', 'text-red-600', 'text-white',
    'bg-blue-600', 'hover:bg-blue-700', 'bg-green-500', 'bg-red-500',
    'mb-5', 'mb-6', 'mb-2', 'mb-4', 'mt-5', 'mt-3', 'mt-4',
    'w-full', 'w-6', 'h-6', 'w-16', 'h-16',
    'rounded-lg', 'rounded-full',
    'inline-block', 'block',
    'cursor-pointer', 'no-underline',
    'transition-colors', 'duration-200',
    'animate-spin',
    'z-[999999]'
  ]
};
```

## API Reference

### WaltVerifyThemes

| Property/Method | Description |
|-----------------|-------------|
| `tailwind` | Default Tailwind CSS theme |
| `cssVariables` | CSS custom properties theme |
| `presets.light` | Light theme preset |
| `presets.dark` | Dark theme preset |
| `presets.minimal` | Minimal theme preset |
| `presets.enterprise` | Enterprise theme preset |
| `extend(base, overrides)` | Extend a theme with custom classes |
| `fromPalette(colors)` | Generate theme from color palette |
| `getCSSVariableStyles(vars)` | Get CSS variable stylesheet content |
| `injectCSSVariableStyles(vars)` | Inject CSS variables into document |

### WaltVerify Theme Methods

| Method | Description |
|--------|-------------|
| `setTheme(theme)` | Update theme at runtime |
| `getTheme()` | Get current theme configuration |
| `getDefaultTheme()` | Get default class-based theme |
