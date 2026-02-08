/**
 * Walt.id Verify Widget Theme System
 * Version: 1.0.0
 *
 * This module provides theme presets and utilities for customizing the
 * verification widget's appearance.
 *
 * Usage:
 *   // With Tailwind CSS
 *   WaltVerify.init({ clientToken: 'ct_xxx', theme: WaltVerifyThemes.tailwind });
 *
 *   // With a preset
 *   WaltVerify.init({ clientToken: 'ct_xxx', theme: WaltVerifyThemes.presets.dark });
 *
 *   // Custom theme
 *   const myTheme = WaltVerifyThemes.extend(WaltVerifyThemes.tailwind, {
 *       primaryButton: 'bg-purple-600 hover:bg-purple-700 text-white rounded-lg'
 *   });
 *
 * @license Apache-2.0
 * @copyright 2024 walt.id GmbH
 */

(function(global, factory) {
    'use strict';
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = factory();
    } else if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else {
        global.WaltVerifyThemes = factory();
    }
})(typeof window !== 'undefined' ? window : this, function() {
    'use strict';

    // ============================================================
    // Theme Interface Documentation
    // ============================================================
    /**
     * @typedef {Object} WidgetTheme
     * @property {string} overlay - Modal overlay (backdrop) classes
     * @property {string} modal - Modal container classes
     * @property {string} header - Modal header container classes
     * @property {string} title - Title text classes
     * @property {string} subtitle - Subtitle/instruction text classes
     * @property {string} content - Content area classes
     * @property {string} qrContainer - QR code wrapper classes
     * @property {string} primaryButton - Primary action button classes
     * @property {string} secondaryButton - Secondary/cancel button classes
     * @property {string} closeButton - Modal close button classes
     * @property {string} deepLinkButton - "Open Wallet" button classes
     * @property {string} divider - "or" divider section classes
     * @property {string} dividerLine - Divider line classes
     * @property {string} dividerText - Divider text classes
     * @property {string} pendingStatus - Pending status indicator classes
     * @property {string} successStatus - Success status indicator classes
     * @property {string} errorStatus - Error status indicator classes
     * @property {string} spinner - Loading spinner classes
     * @property {string} successIcon - Success icon wrapper classes
     * @property {string} errorIcon - Error icon wrapper classes
     * @property {string} footer - Footer container classes
     * @property {string} footerText - Footer text classes
     * @property {string} inlineContainer - Inline mode container classes
     * @property {string} resultSummary - Verification result summary classes
     */

    // ============================================================
    // Default Tailwind Theme
    // ============================================================

    /**
     * Default theme using Tailwind CSS utility classes.
     * This theme provides a clean, modern appearance that works well
     * with most Tailwind-based projects.
     */
    var tailwind = {
        // Modal structure
        overlay: 'fixed inset-0 bg-black/50 flex items-center justify-center z-[999999]',
        modal: 'bg-white rounded-xl shadow-2xl max-w-md w-[90%] max-h-[90vh] overflow-auto relative',
        header: 'px-6 py-5 border-b border-gray-200 flex items-center justify-between',
        content: 'p-6 text-center',
        footer: 'px-6 py-4 border-t border-gray-200 text-center',

        // Typography
        title: 'text-lg font-semibold text-gray-900 m-0',
        subtitle: 'text-base text-gray-900 mb-6 leading-relaxed',
        footerText: 'text-xs text-gray-400',
        statusText: 'text-sm text-gray-500 mb-2',

        // QR Code
        qrContainer: 'bg-white p-4 rounded-lg inline-block mb-5 shadow-sm border border-gray-100',

        // Buttons
        primaryButton: 'w-full inline-block bg-blue-600 hover:bg-blue-700 text-white py-3 px-6 rounded-lg text-base font-medium cursor-pointer border-0 transition-colors duration-200',
        secondaryButton: 'bg-transparent border-0 text-gray-500 text-sm cursor-pointer mt-3 hover:text-gray-700',
        closeButton: 'bg-transparent border-0 cursor-pointer p-2 text-gray-400 text-2xl leading-none hover:text-gray-600',
        deepLinkButton: 'w-full inline-block bg-blue-600 hover:bg-blue-700 text-white py-3 px-6 rounded-lg text-base font-medium no-underline cursor-pointer border-0 transition-colors duration-200 box-border',

        // Divider
        divider: 'flex items-center my-5 text-gray-400',
        dividerLine: 'flex-1 h-px bg-gray-200',
        dividerText: 'px-3 text-sm',

        // Status indicators
        pendingStatus: 'text-sm text-gray-500',
        successStatus: 'text-sm text-green-600 font-medium',
        errorStatus: 'text-sm text-red-600 font-medium',

        // Icons
        successIcon: 'w-16 h-16 bg-green-500 rounded-full flex items-center justify-center mx-auto mb-5',
        errorIcon: 'w-16 h-16 bg-red-500 rounded-full flex items-center justify-center mx-auto mb-5',

        // Loading
        spinner: 'w-6 h-6 border-3 border-gray-200 border-t-blue-600 rounded-full animate-spin mx-auto mb-4',

        // Inline mode
        inlineContainer: 'bg-white rounded-xl border border-gray-200 p-6 text-center',

        // Result display
        resultSummary: 'mt-4 p-3 bg-gray-50 rounded-lg text-left text-sm'
    };

    // ============================================================
    // CSS Variable Theme (for non-Tailwind projects)
    // ============================================================

    /**
     * Theme that uses CSS custom properties (variables).
     * Use this theme with your own CSS file that defines the variables.
     */
    var cssVariables = {
        // Modal structure
        overlay: 'wv-overlay',
        modal: 'wv-modal',
        header: 'wv-header',
        content: 'wv-content',
        footer: 'wv-footer',

        // Typography
        title: 'wv-title',
        subtitle: 'wv-subtitle',
        footerText: 'wv-footer-text',
        statusText: 'wv-status-text',

        // QR Code
        qrContainer: 'wv-qr-container',

        // Buttons
        primaryButton: 'wv-button wv-button-primary',
        secondaryButton: 'wv-button wv-button-secondary',
        closeButton: 'wv-close-button',
        deepLinkButton: 'wv-button wv-button-primary wv-deep-link',

        // Divider
        divider: 'wv-divider',
        dividerLine: 'wv-divider-line',
        dividerText: 'wv-divider-text',

        // Status indicators
        pendingStatus: 'wv-status wv-status-pending',
        successStatus: 'wv-status wv-status-success',
        errorStatus: 'wv-status wv-status-error',

        // Icons
        successIcon: 'wv-icon wv-icon-success',
        errorIcon: 'wv-icon wv-icon-error',

        // Loading
        spinner: 'wv-spinner',

        // Inline mode
        inlineContainer: 'wv-inline-container',

        // Result display
        resultSummary: 'wv-result-summary'
    };

    // ============================================================
    // Theme Presets
    // ============================================================

    var presets = {
        /**
         * Default light theme - clean and professional
         */
        light: tailwind,

        /**
         * Dark theme for dark-mode applications
         */
        dark: {
            overlay: 'fixed inset-0 bg-black/70 flex items-center justify-center z-[999999]',
            modal: 'bg-gray-900 rounded-xl shadow-2xl max-w-md w-[90%] max-h-[90vh] overflow-auto relative border border-gray-700',
            header: 'px-6 py-5 border-b border-gray-700 flex items-center justify-between',
            content: 'p-6 text-center',
            footer: 'px-6 py-4 border-t border-gray-700 text-center',

            title: 'text-lg font-semibold text-white m-0',
            subtitle: 'text-base text-gray-300 mb-6 leading-relaxed',
            footerText: 'text-xs text-gray-500',
            statusText: 'text-sm text-gray-400 mb-2',

            qrContainer: 'bg-white p-4 rounded-lg inline-block mb-5',

            primaryButton: 'w-full inline-block bg-blue-500 hover:bg-blue-600 text-white py-3 px-6 rounded-lg text-base font-medium cursor-pointer border-0 transition-colors duration-200',
            secondaryButton: 'bg-transparent border-0 text-gray-400 text-sm cursor-pointer mt-3 hover:text-gray-200',
            closeButton: 'bg-transparent border-0 cursor-pointer p-2 text-gray-500 text-2xl leading-none hover:text-gray-300',
            deepLinkButton: 'w-full inline-block bg-blue-500 hover:bg-blue-600 text-white py-3 px-6 rounded-lg text-base font-medium no-underline cursor-pointer border-0 transition-colors duration-200 box-border',

            divider: 'flex items-center my-5 text-gray-500',
            dividerLine: 'flex-1 h-px bg-gray-700',
            dividerText: 'px-3 text-sm',

            pendingStatus: 'text-sm text-gray-400',
            successStatus: 'text-sm text-green-400 font-medium',
            errorStatus: 'text-sm text-red-400 font-medium',

            successIcon: 'w-16 h-16 bg-green-600 rounded-full flex items-center justify-center mx-auto mb-5',
            errorIcon: 'w-16 h-16 bg-red-600 rounded-full flex items-center justify-center mx-auto mb-5',

            spinner: 'w-6 h-6 border-3 border-gray-700 border-t-blue-500 rounded-full animate-spin mx-auto mb-4',

            inlineContainer: 'bg-gray-900 rounded-xl border border-gray-700 p-6 text-center',

            resultSummary: 'mt-4 p-3 bg-gray-800 rounded-lg text-left text-sm'
        },

        /**
         * Minimal theme - subtle and unobtrusive
         */
        minimal: {
            overlay: 'fixed inset-0 bg-white/90 flex items-center justify-center z-[999999]',
            modal: 'bg-white max-w-sm w-[90%] max-h-[90vh] overflow-auto relative',
            header: 'px-4 py-4 flex items-center justify-between',
            content: 'px-4 pb-6 text-center',
            footer: 'px-4 py-3 text-center',

            title: 'text-base font-medium text-gray-900 m-0',
            subtitle: 'text-sm text-gray-600 mb-4 leading-relaxed',
            footerText: 'text-xs text-gray-400',
            statusText: 'text-xs text-gray-500 mb-2',

            qrContainer: 'inline-block mb-4',

            primaryButton: 'w-full inline-block bg-gray-900 hover:bg-gray-800 text-white py-2.5 px-4 rounded text-sm font-medium cursor-pointer border-0 transition-colors duration-200',
            secondaryButton: 'bg-transparent border-0 text-gray-500 text-xs cursor-pointer mt-2 hover:text-gray-700',
            closeButton: 'bg-transparent border-0 cursor-pointer p-1 text-gray-400 text-xl leading-none hover:text-gray-600',
            deepLinkButton: 'w-full inline-block bg-gray-900 hover:bg-gray-800 text-white py-2.5 px-4 rounded text-sm font-medium no-underline cursor-pointer border-0 transition-colors duration-200 box-border',

            divider: 'flex items-center my-4 text-gray-400',
            dividerLine: 'flex-1 h-px bg-gray-200',
            dividerText: 'px-2 text-xs',

            pendingStatus: 'text-xs text-gray-500',
            successStatus: 'text-xs text-green-600',
            errorStatus: 'text-xs text-red-600',

            successIcon: 'w-12 h-12 bg-green-500 rounded-full flex items-center justify-center mx-auto mb-4',
            errorIcon: 'w-12 h-12 bg-red-500 rounded-full flex items-center justify-center mx-auto mb-4',

            spinner: 'w-5 h-5 border-2 border-gray-200 border-t-gray-900 rounded-full animate-spin mx-auto mb-3',

            inlineContainer: 'p-4 text-center',

            resultSummary: 'mt-3 p-2 bg-gray-50 rounded text-left text-xs'
        },

        /**
         * Enterprise theme - professional corporate look
         */
        enterprise: {
            overlay: 'fixed inset-0 bg-gray-900/60 flex items-center justify-center z-[999999]',
            modal: 'bg-white rounded-lg shadow-xl max-w-lg w-[90%] max-h-[90vh] overflow-auto relative border border-gray-100',
            header: 'px-8 py-6 border-b border-gray-100 flex items-center justify-between',
            content: 'p-8 text-center',
            footer: 'px-8 py-5 border-t border-gray-100 text-center bg-gray-50',

            title: 'text-xl font-bold text-gray-900 m-0 tracking-tight',
            subtitle: 'text-base text-gray-600 mb-8 leading-relaxed',
            footerText: 'text-xs text-gray-500',
            statusText: 'text-sm text-gray-500 mb-2',

            qrContainer: 'bg-gray-50 p-6 rounded-lg inline-block mb-6 border border-gray-200',

            primaryButton: 'w-full inline-block bg-indigo-600 hover:bg-indigo-700 text-white py-4 px-8 rounded-lg text-base font-semibold cursor-pointer border-0 transition-colors duration-200 shadow-sm',
            secondaryButton: 'bg-transparent border-0 text-gray-500 text-sm cursor-pointer mt-4 hover:text-gray-700 font-medium',
            closeButton: 'bg-transparent border-0 cursor-pointer p-2 text-gray-400 text-2xl leading-none hover:text-gray-600',
            deepLinkButton: 'w-full inline-block bg-indigo-600 hover:bg-indigo-700 text-white py-4 px-8 rounded-lg text-base font-semibold no-underline cursor-pointer border-0 transition-colors duration-200 box-border shadow-sm',

            divider: 'flex items-center my-6 text-gray-400',
            dividerLine: 'flex-1 h-px bg-gray-200',
            dividerText: 'px-4 text-sm font-medium',

            pendingStatus: 'text-sm text-gray-500',
            successStatus: 'text-sm text-emerald-600 font-semibold',
            errorStatus: 'text-sm text-red-600 font-semibold',

            successIcon: 'w-20 h-20 bg-emerald-500 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg',
            errorIcon: 'w-20 h-20 bg-red-500 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg',

            spinner: 'w-8 h-8 border-4 border-gray-200 border-t-indigo-600 rounded-full animate-spin mx-auto mb-4',

            inlineContainer: 'bg-white rounded-lg border border-gray-200 p-8 text-center shadow-sm',

            resultSummary: 'mt-6 p-4 bg-gray-50 rounded-lg text-left border border-gray-100'
        }
    };

    // ============================================================
    // Utility Functions
    // ============================================================

    /**
     * Extend a base theme with custom overrides.
     *
     * @param {WidgetTheme} base - Base theme to extend
     * @param {Partial<WidgetTheme>} overrides - Custom class overrides
     * @returns {WidgetTheme} Extended theme
     */
    function extend(base, overrides) {
        var result = {};
        var keys = Object.keys(base);
        for (var i = 0; i < keys.length; i++) {
            result[keys[i]] = base[keys[i]];
        }
        if (overrides) {
            var overrideKeys = Object.keys(overrides);
            for (var j = 0; j < overrideKeys.length; j++) {
                result[overrideKeys[j]] = overrides[overrideKeys[j]];
            }
        }
        return result;
    }

    /**
     * Create a theme from a color palette.
     * Useful for generating themes from brand colors.
     *
     * @param {Object} colors - Color palette
     * @param {string} colors.primary - Primary brand color (e.g., 'blue-600')
     * @param {string} colors.primaryHover - Primary hover color (e.g., 'blue-700')
     * @param {string} colors.background - Background color (e.g., 'white')
     * @param {string} colors.text - Text color (e.g., 'gray-900')
     * @param {string} colors.textMuted - Muted text color (e.g., 'gray-500')
     * @param {string} colors.border - Border color (e.g., 'gray-200')
     * @param {string} colors.success - Success color (e.g., 'green-500')
     * @param {string} colors.error - Error color (e.g., 'red-500')
     * @returns {WidgetTheme} Generated theme
     */
    function fromPalette(colors) {
        var p = colors.primary || 'blue-600';
        var pHover = colors.primaryHover || 'blue-700';
        var bg = colors.background || 'white';
        var text = colors.text || 'gray-900';
        var textMuted = colors.textMuted || 'gray-500';
        var border = colors.border || 'gray-200';
        var success = colors.success || 'green-500';
        var error = colors.error || 'red-500';

        return {
            overlay: 'fixed inset-0 bg-black/50 flex items-center justify-center z-[999999]',
            modal: 'bg-' + bg + ' rounded-xl shadow-2xl max-w-md w-[90%] max-h-[90vh] overflow-auto relative',
            header: 'px-6 py-5 border-b border-' + border + ' flex items-center justify-between',
            content: 'p-6 text-center',
            footer: 'px-6 py-4 border-t border-' + border + ' text-center',

            title: 'text-lg font-semibold text-' + text + ' m-0',
            subtitle: 'text-base text-' + text + ' mb-6 leading-relaxed',
            footerText: 'text-xs text-' + textMuted,
            statusText: 'text-sm text-' + textMuted + ' mb-2',

            qrContainer: 'bg-white p-4 rounded-lg inline-block mb-5 shadow-sm border border-' + border,

            primaryButton: 'w-full inline-block bg-' + p + ' hover:bg-' + pHover + ' text-white py-3 px-6 rounded-lg text-base font-medium cursor-pointer border-0 transition-colors duration-200',
            secondaryButton: 'bg-transparent border-0 text-' + textMuted + ' text-sm cursor-pointer mt-3 hover:text-' + text,
            closeButton: 'bg-transparent border-0 cursor-pointer p-2 text-' + textMuted + ' text-2xl leading-none hover:text-' + text,
            deepLinkButton: 'w-full inline-block bg-' + p + ' hover:bg-' + pHover + ' text-white py-3 px-6 rounded-lg text-base font-medium no-underline cursor-pointer border-0 transition-colors duration-200 box-border',

            divider: 'flex items-center my-5 text-' + textMuted,
            dividerLine: 'flex-1 h-px bg-' + border,
            dividerText: 'px-3 text-sm',

            pendingStatus: 'text-sm text-' + textMuted,
            successStatus: 'text-sm text-' + success.replace('-500', '-600') + ' font-medium',
            errorStatus: 'text-sm text-' + error.replace('-500', '-600') + ' font-medium',

            successIcon: 'w-16 h-16 bg-' + success + ' rounded-full flex items-center justify-center mx-auto mb-5',
            errorIcon: 'w-16 h-16 bg-' + error + ' rounded-full flex items-center justify-center mx-auto mb-5',

            spinner: 'w-6 h-6 border-3 border-' + border + ' border-t-' + p + ' rounded-full animate-spin mx-auto mb-4',

            inlineContainer: 'bg-' + bg + ' rounded-xl border border-' + border + ' p-6 text-center',

            resultSummary: 'mt-4 p-3 bg-gray-50 rounded-lg text-left text-sm'
        };
    }

    /**
     * Get CSS variable stylesheet content.
     * Include this in your page to use the cssVariables theme.
     *
     * @param {Object} [vars] - Custom CSS variable values
     * @returns {string} CSS stylesheet content
     */
    function getCSSVariableStyles(vars) {
        vars = vars || {};

        var defaults = {
            '--wv-primary': vars.primary || '#2563eb',
            '--wv-primary-hover': vars.primaryHover || '#1d4ed8',
            '--wv-background': vars.background || '#ffffff',
            '--wv-background-alt': vars.backgroundAlt || '#f9fafb',
            '--wv-text': vars.text || '#1f2937',
            '--wv-text-muted': vars.textMuted || '#6b7280',
            '--wv-text-light': vars.textLight || '#9ca3af',
            '--wv-border': vars.border || '#e5e7eb',
            '--wv-success': vars.success || '#10b981',
            '--wv-error': vars.error || '#ef4444',
            '--wv-overlay': vars.overlay || 'rgba(0, 0, 0, 0.5)',
            '--wv-radius': vars.radius || '12px',
            '--wv-radius-sm': vars.radiusSm || '8px',
            '--wv-font-family': vars.fontFamily || '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
            '--wv-shadow': vars.shadow || '0 25px 50px -12px rgba(0, 0, 0, 0.25)'
        };

        var css = [
            ':root {',
            Object.keys(defaults).map(function(key) {
                return '  ' + key + ': ' + defaults[key] + ';';
            }).join('\n'),
            '}',
            '',
            '/* WaltVerify Widget Styles */',
            '.wv-overlay {',
            '  position: fixed;',
            '  inset: 0;',
            '  background-color: var(--wv-overlay);',
            '  display: flex;',
            '  align-items: center;',
            '  justify-content: center;',
            '  z-index: 999999;',
            '  font-family: var(--wv-font-family);',
            '}',
            '',
            '.wv-modal {',
            '  background-color: var(--wv-background);',
            '  border-radius: var(--wv-radius);',
            '  box-shadow: var(--wv-shadow);',
            '  max-width: 420px;',
            '  width: 90%;',
            '  max-height: 90vh;',
            '  overflow: auto;',
            '  position: relative;',
            '}',
            '',
            '.wv-header {',
            '  padding: 20px 24px;',
            '  border-bottom: 1px solid var(--wv-border);',
            '  display: flex;',
            '  align-items: center;',
            '  justify-content: space-between;',
            '}',
            '',
            '.wv-content {',
            '  padding: 24px;',
            '  text-align: center;',
            '}',
            '',
            '.wv-footer {',
            '  padding: 16px 24px;',
            '  border-top: 1px solid var(--wv-border);',
            '  text-align: center;',
            '}',
            '',
            '.wv-title {',
            '  font-size: 18px;',
            '  font-weight: 600;',
            '  color: var(--wv-text);',
            '  margin: 0;',
            '}',
            '',
            '.wv-subtitle {',
            '  font-size: 16px;',
            '  color: var(--wv-text);',
            '  margin-bottom: 24px;',
            '  line-height: 1.5;',
            '}',
            '',
            '.wv-footer-text {',
            '  font-size: 12px;',
            '  color: var(--wv-text-light);',
            '}',
            '',
            '.wv-status-text {',
            '  font-size: 14px;',
            '  color: var(--wv-text-muted);',
            '  margin-bottom: 8px;',
            '}',
            '',
            '.wv-qr-container {',
            '  background-color: #ffffff;',
            '  padding: 16px;',
            '  border-radius: var(--wv-radius-sm);',
            '  display: inline-block;',
            '  margin-bottom: 20px;',
            '}',
            '',
            '.wv-button {',
            '  display: inline-block;',
            '  padding: 12px 24px;',
            '  border-radius: var(--wv-radius-sm);',
            '  font-size: 16px;',
            '  font-weight: 500;',
            '  cursor: pointer;',
            '  transition: background-color 0.2s, color 0.2s;',
            '  text-decoration: none;',
            '  border: none;',
            '  width: 100%;',
            '  box-sizing: border-box;',
            '}',
            '',
            '.wv-button-primary {',
            '  background-color: var(--wv-primary);',
            '  color: #ffffff;',
            '}',
            '',
            '.wv-button-primary:hover {',
            '  background-color: var(--wv-primary-hover);',
            '}',
            '',
            '.wv-button-secondary {',
            '  background: none;',
            '  color: var(--wv-text-muted);',
            '  font-size: 14px;',
            '  width: auto;',
            '  padding: 8px;',
            '  margin-top: 12px;',
            '}',
            '',
            '.wv-button-secondary:hover {',
            '  color: var(--wv-text);',
            '}',
            '',
            '.wv-close-button {',
            '  background: none;',
            '  border: none;',
            '  cursor: pointer;',
            '  padding: 8px;',
            '  color: var(--wv-text-muted);',
            '  font-size: 24px;',
            '  line-height: 1;',
            '}',
            '',
            '.wv-close-button:hover {',
            '  color: var(--wv-text);',
            '}',
            '',
            '.wv-divider {',
            '  display: flex;',
            '  align-items: center;',
            '  margin: 20px 0;',
            '  color: var(--wv-text-light);',
            '}',
            '',
            '.wv-divider-line {',
            '  flex: 1;',
            '  height: 1px;',
            '  background-color: var(--wv-border);',
            '}',
            '',
            '.wv-divider-text {',
            '  padding: 0 12px;',
            '  font-size: 14px;',
            '}',
            '',
            '.wv-status {',
            '  font-size: 14px;',
            '}',
            '',
            '.wv-status-pending {',
            '  color: var(--wv-text-muted);',
            '}',
            '',
            '.wv-status-success {',
            '  color: var(--wv-success);',
            '  font-weight: 500;',
            '}',
            '',
            '.wv-status-error {',
            '  color: var(--wv-error);',
            '  font-weight: 500;',
            '}',
            '',
            '.wv-icon {',
            '  width: 64px;',
            '  height: 64px;',
            '  border-radius: 50%;',
            '  display: flex;',
            '  align-items: center;',
            '  justify-content: center;',
            '  margin: 0 auto 20px;',
            '}',
            '',
            '.wv-icon-success {',
            '  background-color: var(--wv-success);',
            '}',
            '',
            '.wv-icon-error {',
            '  background-color: var(--wv-error);',
            '}',
            '',
            '.wv-spinner {',
            '  width: 24px;',
            '  height: 24px;',
            '  border: 3px solid var(--wv-border);',
            '  border-top-color: var(--wv-primary);',
            '  border-radius: 50%;',
            '  animation: wv-spin 1s linear infinite;',
            '  margin: 0 auto 16px;',
            '}',
            '',
            '@keyframes wv-spin {',
            '  from { transform: rotate(0deg); }',
            '  to { transform: rotate(360deg); }',
            '}',
            '',
            '.wv-inline-container {',
            '  background-color: var(--wv-background);',
            '  border-radius: var(--wv-radius);',
            '  border: 1px solid var(--wv-border);',
            '  padding: 24px;',
            '  text-align: center;',
            '  font-family: var(--wv-font-family);',
            '}',
            '',
            '.wv-result-summary {',
            '  margin-top: 16px;',
            '  padding: 12px;',
            '  background-color: var(--wv-background-alt);',
            '  border-radius: var(--wv-radius-sm);',
            '  text-align: left;',
            '  font-size: 14px;',
            '}',
            '',
            '.wv-deep-link {',
            '  margin-bottom: 16px;',
            '}'
        ].join('\n');

        return css;
    }

    /**
     * Inject CSS variable styles into the document.
     *
     * @param {Object} [vars] - Custom CSS variable values
     */
    function injectCSSVariableStyles(vars) {
        if (typeof document === 'undefined') return;

        var styleId = 'waltverify-theme-styles';
        var existing = document.getElementById(styleId);
        if (existing) {
            existing.remove();
        }

        var style = document.createElement('style');
        style.id = styleId;
        style.textContent = getCSSVariableStyles(vars);
        document.head.appendChild(style);
    }

    // ============================================================
    // Public API
    // ============================================================

    return {
        /**
         * Default Tailwind CSS theme
         */
        tailwind: tailwind,

        /**
         * CSS Variables theme (requires companion stylesheet)
         */
        cssVariables: cssVariables,

        /**
         * Pre-built theme presets
         */
        presets: presets,

        /**
         * Extend a theme with custom overrides
         */
        extend: extend,

        /**
         * Create a theme from a color palette
         */
        fromPalette: fromPalette,

        /**
         * Get CSS variable stylesheet content
         */
        getCSSVariableStyles: getCSSVariableStyles,

        /**
         * Inject CSS variable styles into the document
         */
        injectCSSVariableStyles: injectCSSVariableStyles,

        /**
         * Theme version
         */
        version: '1.0.0'
    };
});
