document.addEventListener('DOMContentLoaded', () => {
    const status = document.getElementById('status');
    // El service worker no puede comunicarse directamente con popup en MV3,
    // pero podemos verificar conexión vía fetch al WS (no ideal) o
    // simplemente mostrar instrucciones.
    status.textContent = 'Ver consola del service worker';
});