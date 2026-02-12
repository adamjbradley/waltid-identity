const sendToWebWallet = (walletUrl: String, path: String, requestUrl: String, metadata?: Record<string, string>) => {
    let request = requestUrl.replaceAll("\n", "").trim()
    let url = `${walletUrl}/${path}` + request.substring(request.indexOf('?'));
    if (metadata && Object.keys(metadata).length > 0) {
        const params = new URLSearchParams(metadata);
        url += '&' + params.toString();
    }
    window.location.href = url;
}

export {sendToWebWallet};
