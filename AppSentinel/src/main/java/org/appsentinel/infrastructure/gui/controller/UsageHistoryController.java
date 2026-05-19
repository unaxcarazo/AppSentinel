package org.appsentinel.infrastructure.gui.controller;

// IMPORTS CON LAS RUTAS EXACTAS DE TUS PAQUETES
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.RegistroRepositoryPort;

// IMPORTS JAVAFX Y JAVA
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.appsentinel.infrastructure.config.AppContext;

public class UsageHistoryController implements Controllable {

    private RegistroRepositoryPort registroRepository;

    public UsageHistoryController() {
        // Constructor vacío para permitir la inyección de dependencias diferida
    }

    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> comboFilter;
    @FXML
    private VBox vboxTableRows;

    // Uso estricto de la entidad de dominio real 'Registro'
    private List<Registro> listaCompletaMaster;

    @FXML
    public void initialize() {
        // 1. Configuración de la interfaz visual base
        comboFilter.getItems().clear();
        comboFilter.getItems().addAll("All Activities", "Work Only", "Distractions Only");
        comboFilter.setValue("All Activities");

        // 2. Escuchadores listos para reaccionar cuando el usuario filtre en tiempo real
        txtSearch.textProperty().addListener((observable, oldValue, newValue) -> aplicarFiltrosCombinados());
        comboFilter.valueProperty().addListener((observable, oldValue, newValue) -> aplicarFiltrosCombinados());
    }

    

    private void aplicarFiltrosCombinados() {
        // Evitamos fallos si el usuario busca antes de que se complete el método init()
        if (listaCompletaMaster == null) {
            return;
        }

        String textoBusqueda = txtSearch.getText().toLowerCase().trim();
        String opcionFiltro = comboFilter.getValue();

        List<Registro> listaFiltrada = listaCompletaMaster.stream()
                .filter(item -> item.getNombreActividad().toLowerCase().contains(textoBusqueda))
                .filter(item -> {
                    if ("Work Only".equals(opcionFiltro)) {
                        return "TRABAJO".equals(item.getCategoria());
                    } else if ("Distractions Only".equals(opcionFiltro)) {
                        return "DISTRACCION".equals(item.getCategoria());
                    }
                    return true;
                })
                .collect(Collectors.toList());

        actualizarTabla(listaFiltrada);
    }

    private void actualizarTabla(List<Registro> lista) {
        vboxTableRows.getChildren().clear();

        if (lista == null || lista.isEmpty()) {
            HBox emptyRow = new HBox(new Label("No records found matches criteria."));
            emptyRow.setAlignment(Pos.CENTER);
            emptyRow.setPadding(new javafx.geometry.Insets(20));
            vboxTableRows.getChildren().add(emptyRow);
            return;
        }

        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        for (Registro item : lista) {
            HBox row = new HBox();
            row.getStyleClass().add("table-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(10);

            // 1. Bloque Nombre + Categoría perfectamente alineados
            VBox nameBlock = new VBox(5);
            nameBlock.setPrefWidth(250);
            nameBlock.setMinWidth(250);
            nameBlock.setMaxWidth(250);
            nameBlock.setAlignment(Pos.CENTER_LEFT);

            Label lblName = new Label(item.getNombreActividad());
            lblName.getStyleClass().add("app-name");

            HBox catBadgeContainer = new HBox();
            Label lblCat = new Label(item.getCategoria());

            if ("TRABAJO".equalsIgnoreCase(item.getCategoria())) {
                lblCat.getStyleClass().add("badge-cat-work");
            } else {
                lblCat.getStyleClass().add("badge-cat-distraction");
            }
            catBadgeContainer.getChildren().add(lblCat);
            nameBlock.getChildren().addAll(lblName, catBadgeContainer);

            // Regiones espaciadoras elásticas
            Region spacer1 = new Region();
            HBox.setHgrow(spacer1, Priority.ALWAYS);
            Region spacer2 = new Region();
            HBox.setHgrow(spacer2, Priority.ALWAYS);
            Region spacer3 = new Region();
            HBox.setHgrow(spacer3, Priority.ALWAYS);

            // 2. Celda: Hora de Inicio
            String horaFormateada = "00:00:00";
            if (item.getFechaRegistro() != null) {
                horaFormateada = item.getFechaRegistro().format(timeFormatter);
            }
            Label lblStartTime = new Label(horaFormateada);
            lblStartTime.getStyleClass().add("table-cell-text");
            lblStartTime.setPrefWidth(120);
            lblStartTime.setAlignment(Pos.CENTER);

            // 3. Celda: Duración
            Label lblDuration = new Label(formatearTiempo(item.getDuracionSeg()));
            lblDuration.getStyleClass().add("table-cell-text");
            lblDuration.setPrefWidth(120);
            lblDuration.setAlignment(Pos.CENTER);

            // 4. Celda: Estado Bloqueo
            boolean esBloqueado = "BLOCKED".equalsIgnoreCase(item.getDetalle())
                    || ("DISTRACCION".equalsIgnoreCase(item.getCategoria()) && item.getDuracionSeg() == 0);

            Label lblStatus = new Label(esBloqueado ? "BLOCKED" : "ALLOWED");
            lblStatus.setPrefWidth(100);
            lblStatus.setAlignment(Pos.CENTER);

            if (!esBloqueado) {
                lblStatus.getStyleClass().add("badge-status-allowed");
            } else {
                lblStatus.getStyleClass().add("badge-status-blocked");
            }

            // Ensamblaje estructural
            row.getChildren().addAll(nameBlock, spacer1, lblStartTime, spacer2, lblDuration, spacer3, lblStatus);
            vboxTableRows.getChildren().add(row);
        }
    }

    private String formatearTiempo(long segundosTotales) {
        if (segundosTotales <= 0) {
            return "00h 00m";
        }
        long horas = segundosTotales / 3600;
        long minutes = (segundosTotales % 3600) / 60;
        return String.format("%02dh %02dm", horas, minutes);
    }

    // =========================================================================
    // 🔄 MÉTODO DE INICIALIZACIÓN CONTRATADO POR LA INTERFAZ CONTROLLABLE
    // =========================================================================
    @Override
    public void init(AppContext ctx) {
        // 1. Extraemos el puerto de registros desde el contexto unificado de tu compañera
        this.registroRepository = ctx.repositorio();

        // 2. Extraemos el usuario del sistema operativo de forma segura (tu lógica original)
        String usuarioActual = System.getProperty("user.name");
        if (usuarioActual == null) {
            usuarioActual = "DAW1";
        }

        // 3. Cargamos los datos reales desde la base de datos de PostgreSQL
        this.listaCompletaMaster = registroRepository.obtenerHistorialCompleto();

        // 4. Renderizamos la tabla elástica por primera vez
        actualizarTabla(this.listaCompletaMaster);

        System.out.println("⏳ UsageHistory cargado con éxito en el ecosistema del grupo usando AppContext.");
    }
}
