/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.appsentinel.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 *
 * @author DAW1
 */
public class DatabaseConnection {
    
    private static final String URL = "jdbc:postgresql://localhost:5432/appsentinel";
    private static final String USER = "postgres"; 
    private static final String PASSWORD = "PON_AQUI_TU_CONTRASEÑA_DE_PGADMIN"; 

    public static Connection getConnection() throws SQLException {
        try {
            return DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            System.err.println("Error de conexión: " + e.getMessage());
            throw e;
        }
    }
}

