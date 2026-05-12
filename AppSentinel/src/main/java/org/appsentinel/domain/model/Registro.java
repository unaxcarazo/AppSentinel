package org.appsentinel.domain.model;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Registro: Entidad principal del dominio.
 * Representa una actividad detectada (app o web) con su duración y clasificación.
 * 
 * @Data = @Getter + @Setter + @ToString + @EqualsAndHashCode
 * @Builder = Permite construir objetos tipo: Registro.builder().nombre("Chrome").build()
 * @NoArgsConstructor / @AllArgsConstructor = Constructores vacío y completo
 */
public class Registro {
    
    private Long id;
    private String usuarioSistema;
    private String nombreActividad;
    private String categoria;
    private String detalle;
    private long duracionSeg;
    private LocalDateTime fechaRegistro;

    // ============================================
    // CONSTRUCTORES
    // ============================================
    
    /**
     * Constructor vacío requerido por frameworks y para construcción paso a paso.
     */
    public Registro() {
    }

    /**
     * Constructor completo con todos los campos.
     */
    public Registro(Long id, String usuarioSistema, String nombreActividad, 
                    String categoria, String detalle, long duracionSeg, 
                    LocalDateTime fechaRegistro) {
        this.id = id;
        this.usuarioSistema = usuarioSistema;
        this.nombreActividad = nombreActividad;
        this.categoria = categoria;
        this.detalle = detalle;
        this.duracionSeg = duracionSeg;
        this.fechaRegistro = fechaRegistro;
    }

    // ============================================
    // GETTERS
    // ============================================
    
    public Long getId() {
        return id;
    }

    public String getUsuarioSistema() {
        return usuarioSistema;
    }

    public String getNombreActividad() {
        return nombreActividad;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getDetalle() {
        return detalle;
    }

    public long getDuracionSeg() {
        return duracionSeg;
    }

    public LocalDateTime getFechaRegistro() {
        return fechaRegistro;
    }

    // ============================================
    // SETTERS
    // ============================================
    
    public void setId(Long id) {
        this.id = id;
    }

    public void setUsuarioSistema(String usuarioSistema) {
        this.usuarioSistema = usuarioSistema;
    }

    public void setNombreActividad(String nombreActividad) {
        this.nombreActividad = nombreActividad;
    }

    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    public void setDetalle(String detalle) {
        this.detalle = detalle;
    }

    public void setDuracionSeg(long duracionSeg) {
        this.duracionSeg = duracionSeg;
    }

    public void setFechaRegistro(LocalDateTime fechaRegistro) {
        this.fechaRegistro = fechaRegistro;
    }

    // ============================================
    // EQUALS Y HASHCODE (basados en id si existe, sino en todos los campos)
    // ============================================
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Registro registro = (Registro) o;
        return duracionSeg == registro.duracionSeg &&
               Objects.equals(id, registro.id) &&
               Objects.equals(usuarioSistema, registro.usuarioSistema) &&
               Objects.equals(nombreActividad, registro.nombreActividad) &&
               Objects.equals(categoria, registro.categoria) &&
               Objects.equals(detalle, registro.detalle) &&
               Objects.equals(fechaRegistro, registro.fechaRegistro);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, usuarioSistema, nombreActividad, categoria, 
                           detalle, duracionSeg, fechaRegistro);
    }

    // ============================================
    // TOSTRING
    // ============================================
    
    @Override
    public String toString() {
        return "Registro{" +
               "id=" + id +
               ", usuarioSistema='" + usuarioSistema + '\'' +
               ", nombreActividad='" + nombreActividad + '\'' +
               ", categoria='" + categoria + '\'' +
               ", detalle='" + detalle + '\'' +
               ", duracionSeg=" + duracionSeg +
               ", fechaRegistro=" + fechaRegistro +
               '}';
    }

    // ============================================
    // BUILDER MANUAL (patrón Builder sin Lombok)
    // ============================================
    
    /**
     * Crea un nuevo builder para construir un Registro paso a paso.
     * Uso: Registro registro = Registro.builder().nombreActividad("Chrome").categoria("NEUTRAL").build();
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String usuarioSistema;
        private String nombreActividad;
        private String categoria;
        private String detalle;
        private long duracionSeg;
        private LocalDateTime fechaRegistro;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder usuarioSistema(String usuarioSistema) {
            this.usuarioSistema = usuarioSistema;
            return this;
        }

        public Builder nombreActividad(String nombreActividad) {
            this.nombreActividad = nombreActividad;
            return this;
        }

        public Builder categoria(String categoria) {
            this.categoria = categoria;
            return this;
        }

        public Builder detalle(String detalle) {
            this.detalle = detalle;
            return this;
        }

        public Builder duracionSeg(long duracionSeg) {
            this.duracionSeg = duracionSeg;
            return this;
        }

        public Builder fechaRegistro(LocalDateTime fechaRegistro) {
            this.fechaRegistro = fechaRegistro;
            return this;
        }

        public Registro build() {
            return new Registro(id, usuarioSistema, nombreActividad, 
                              categoria, detalle, duracionSeg, fechaRegistro);
        }
    }
}