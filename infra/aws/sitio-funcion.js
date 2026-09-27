// CloudFront Function (viewer-request) del sitio elpuesto.app. S3 con OAC no resuelve
// "carpetas": /descargas/ → /descargas/index.html, y /descargas (sin diagonal) redirige a
// /descargas/ para que los enlaces relativos de la página funcionen. www → apex.
function handler(event) {
    var req = event.request;
    var host = req.headers.host ? req.headers.host.value : '';
    if (host === 'www.elpuesto.app') {
        return {
            statusCode: 301, statusDescription: 'Moved Permanently',
            headers: { location: { value: 'https://elpuesto.app' + req.uri } },
        };
    }
    var uri = req.uri;
    if (uri.endsWith('/')) {
        req.uri = uri + 'index.html';
        return req;
    }
    var ultimo = uri.substring(uri.lastIndexOf('/') + 1);
    if (ultimo.indexOf('.') === -1) {
        return {
            statusCode: 301, statusDescription: 'Moved Permanently',
            headers: { location: { value: uri + '/' } },
        };
    }
    return req;
}
