package org.appsentinel.infrastructure.adapter.out;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import org.appsentinel.domain.model.Registro;
import org.appsentinel.domain.port.out.ReportPort;

public class HtmlReportAdapter implements ReportPort {

    @Override
    public void generarReporteHtml(List<Registro> registros, String rutaSalida) {
        StringBuilder html = new StringBuilder();

        // 1. Cabecera del HTML (CSS incluido)
        html.append("<!DOCTYPE html><html lang='es'><head><meta charset='UTF-8'><title>AppSentinel Report</title>")
                .append("<style>")
                .append("body { font-family: sans-serif; background: #f4f7f9; padding: 20px; }")
                .append(".header { background: #0d1117; color: white; padding: 20px; border-radius: 10px; text-align: center; }")
                .append(".container { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; margin-top: 20px; }")
                .append(".card { background: white; border-radius: 12px; padding: 20px; border-left: 8px solid #ccc; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }")
                .append(".card.TRABAJO { border-left-color: #00d2ff; }")
                .append(".card.DISTRACCION { border-left-color: #ff5c00; }")
                .append("h3 { margin: 0; } .duration { font-size: 1.5em; font-weight: bold; }")
                .append("</style></head><body>")
                .append("<div class='header'><h1>AppSentinel - Resumen de Actividad</h1></div>")
                .append("<div class='container'>");

        // 2. Generar una tarjeta por cada registro de la lista
        for (Registro r : registros) {
            String categoria = r.getCategoria(); // Asegúrate de que tu modelo Registro tenga getCategoria()
            html.append("<div class='card ").append(categoria).append("'>")
                    .append("<span>").append(categoria).append("</span>")
                    .append("<h3>").append(r.getNombreActividad()).append("</h3>")
                    .append("<div class='duration'>").append(r.getDuracionSeg()).append(" seg.</div>")
                    .append("</div>");
        }

        html.append("</div></body></html>");

        // 3. Escribir el archivo en el disco
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(rutaSalida)))) {
            writer.write(html.toString());
            System.out.println("Informe creado con éxito en: " + rutaSalida);
        } catch (IOException e) {
            System.err.println("Error al escribir el HTML: " + e.getMessage());
        }
    }
}
