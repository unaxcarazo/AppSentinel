package org.appsentinel.infrastructure.adapter.out;

import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class FakeRegistroAdapter implements RegistroRepositoryPort {

    @Override
    public void guardar(Registro registro) {
        System.out.println("Simulando guardado de: " + registro.getNombreActividad());
    }

    // Este método centraliza los datos de prueba para no repetir código
    private List<Registro> generarDatosPrueba() {
        List<Registro> lista = new ArrayList<>();
        lista.add(Registro.builder().nombreActividad("VS Code").categoria("TRABAJO").duracionSeg(15120).fechaRegistro(LocalDateTime.now().minusHours(4)).build());
        lista.add(Registro.builder().nombreActividad("Figma").categoria("TRABAJO").duracionSeg(7920).fechaRegistro(LocalDateTime.now().minusHours(2)).build());
        lista.add(Registro.builder().nombreActividad("Notion").categoria("TRABAJO").duracionSeg(2520).fechaRegistro(LocalDateTime.now().minusHours(1)).build());
        lista.add(Registro.builder().nombreActividad("YouTube").categoria("DISTRACCION").duracionSeg(4200).fechaRegistro(LocalDateTime.now().minusMinutes(30)).build());
        lista.add(Registro.builder().nombreActividad("Twitter").categoria("DISTRACCION").duracionSeg(1200).fechaRegistro(LocalDateTime.now().minusMinutes(10)).build());
        return lista;
    }

    @Override
    public List<Registro> obtenerTodosHoy(String usuario) {
        return generarDatosPrueba();
    }

    @Override
    public List<Registro> obtenerActividadHoy(String usuario) {
        return generarDatosPrueba();
    }

    @Override
    public List<Registro> obtenerPorCategoria(String usuario, String categoria) {
        return generarDatosPrueba().stream()
                .filter(r -> r.getCategoria().equalsIgnoreCase(categoria))
                .collect(Collectors.toList());
    }

    @Override
    public List<Registro> obtenerTopDistracciones(String usuario, int limite) {
        return generarDatosPrueba().stream()
                .filter(r -> "DISTRACCION".equals(r.getCategoria()))
                .sorted((a, b) -> Long.compare(b.getDuracionSeg(), a.getDuracionSeg()))
                .limit(limite)
                .collect(Collectors.toList());
    }

    @Override
    public List<Registro> obtenerTopTrabajo(String usuario, int limite) {
        return generarDatosPrueba().stream()
                .filter(r -> "TRABAJO".equals(r.getCategoria()))
                .sorted((a, b) -> Long.compare(b.getDuracionSeg(), a.getDuracionSeg())) // Ordenar por duración
                .limit(limite)
                .collect(Collectors.toList());
    }

    @Override
    public List<Registro> obtenerBloqueosHoy(String usuario) {
        List<Registro> bloqueos = new ArrayList<>();
        bloqueos.add(Registro.builder()
                .nombreActividad("Instagram")
                .categoria("DISTRACCION")
                .detalle("Intento de acceso bloqueado")
                .fechaRegistro(LocalDateTime.now().minusMinutes(5))
                .build());
        return bloqueos;
    }
}