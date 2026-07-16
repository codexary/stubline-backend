package com.elsworne.stubline.notification;

import java.sql.DriverManager;

/** Container probe for the notification database and migrated table. */
public final class NotificationHealthCheck {
  private NotificationHealthCheck() {}

  public static void main(String[] args) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                System.getenv("STUBLINE_JDBC_URL"),
                System.getenv("STUBLINE_DB_USER"),
                System.getenv("STUBLINE_DB_PASSWORD"));
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT count(*) FROM notifications")) {
      if (!result.next()) System.exit(1);
    }
  }
}
