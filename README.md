# JasperReports Development Tool

A Spring Boot application for developing and testing JasperReports JRXML files with multiple database support.

## Features

- **Report Management**: Auto-detects JRXML files with hot-reload support
- **Multiple Data Sources**: Configure multiple PostgreSQL databases via `datasources.properties`
- **Dynamic Parameter Forms**: Auto-generates input forms based on JRXML parameters
- **PDF Generation**: Compile and run reports with real-time PDF preview
- **Railway Deployment**: Ready for cloud deployment

## Quick Start

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- PostgreSQL database(s)

### Local Development

1. **Configure data sources** - Edit `src/main/resources/datasources.properties`:

```properties
# Demo Environment
datasource.demo.url=jdbc:postgresql://localhost:5432/loanwatch_demo
datasource.demo.username=postgres
datasource.demo.password=yourpassword
datasource.demo.driver=org.postgresql.Driver

# Test Environment
datasource.test.url=jdbc:postgresql://localhost:5432/loanwatch_test
datasource.test.username=postgres
datasource.test.password=yourpassword
datasource.test.driver=org.postgresql.Driver
```

2. **Run the application**:

```bash
mvn spring-boot:run
```

3. **Open in browser**: http://localhost:8080

### Adding New Reports

1. Create your JRXML file in Jaspersoft Studio
2. Save it to `src/main/resources/reports/`
3. The application auto-detects new files (refresh the home page)

### Adding New Data Sources

Edit `datasources.properties` and add a new entry:

```properties
datasource.production.url=jdbc:postgresql://prod-server:5432/loanwatch_prod
datasource.production.username=prod_user
datasource.production.password=prod_password
datasource.production.driver=org.postgresql.Driver
```

Restart the application to load new data sources.

## Railway Deployment

### Initial Setup

1. Create a new Railway project
2. Connect your GitHub repository
3. Railway will auto-detect the Java app

### Environment Variables

Set these environment variables in Railway for each data source:

| Variable | Description | Example |
|----------|-------------|---------|
| `DEMO_DB_HOST` | Demo database host | `db.example.com` |
| `DEMO_DB_PORT` | Demo database port | `5432` |
| `DEMO_DB_NAME` | Demo database name | `loanwatch_demo` |
| `DEMO_DB_USER` | Demo database user | `app_user` |
| `DEMO_DB_PASSWORD` | Demo database password | `secret` |
| `TEST_DB_HOST` | Test database host | `test-db.example.com` |
| `TEST_DB_PORT` | Test database port | `5432` |
| `TEST_DB_NAME` | Test database name | `loanwatch_test` |
| `TEST_DB_USER` | Test database user | `test_user` |
| `TEST_DB_PASSWORD` | Test database password | `secret` |

Railway automatically sets the `PORT` environment variable.

### Deployment

Push to your connected branch - Railway auto-deploys:

```bash
git add .
git commit -m "Update reports"
git push
```

## Project Structure

```
├── src/main/java/com/jasperdev/reports/
│   ├── JasperReportsApplication.java   # Main entry point
│   ├── controller/
│   │   └── ReportController.java       # HTTP endpoints
│   ├── service/
│   │   ├── DataSourceService.java      # Database connection management
│   │   └── ReportService.java          # Report compilation & generation
│   ├── model/
│   │   ├── DataSourceConfig.java       # Data source configuration
│   │   ├── ReportInfo.java             # Report metadata
│   │   └── ReportParameter.java        # Parameter metadata
│   └── util/
│       └── ParameterExtractor.java     # JRXML parsing
├── src/main/resources/
│   ├── reports/                        # JRXML files go here
│   │   └── LW Borrower Customers.jrxml
│   ├── templates/                      # Thymeleaf templates
│   │   ├── home.html
│   │   ├── report-form.html
│   │   └── error.html
│   ├── static/css/
│   │   └── style.css
│   ├── application.properties
│   └── datasources.properties
├── pom.xml
├── railway.json
└── README.md
```

## Sample Report

The included `LW Borrower Customers.jrxml` report demonstrates:

- **Parameter**: `Borrower Key` (Integer) - Filter by borrower ID, or 0 for all
- **Query**: Joins `ar.ar_customer` with `borrower.borrower`

### Sample Query

This SQL works with the sample report:

```sql
SELECT a.borrower_fk,
       a.cust_name,
       a.cust_city,
       a.cust_state,
       a.cust_country,
       b.borrower_name
FROM ar.ar_customer a
JOIN borrower.borrower b ON a.borrower_fk = b.record_id
WHERE ($P{Borrower Key} = 0 OR a.borrower_fk = $P{Borrower Key})
ORDER BY b.borrower_name ASC, a.cust_name ASC
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Home page with report list |
| `/report/{id}` | GET | Parameter form for a report |
| `/report/{id}/generate` | POST | Generate PDF |
| `/api/refresh` | POST | Refresh report list |
| `/api/datasource/{name}/test` | GET | Test data source connection |

## Troubleshooting

### Report not showing in list

- Verify the file has `.jrxml` extension
- Check file is in `src/main/resources/reports/`
- Click "Refresh List" or restart the application

### Database connection failed

- Test the connection using the "Test" button
- Verify credentials in `datasources.properties`
- Check database server is accessible from your network
- Review application logs for detailed error messages

### PDF generation error

- Check all required parameters have values
- Verify the selected data source has the required tables/schemas
- Review the JRXML file for SQL syntax errors
- Check application logs for stack traces

### Parameter not appearing in form

- Ensure parameter has `isForPrompting="true"` (or no attribute, true is default)
- Built-in JasperReports parameters are filtered out

## Development Tips

1. **Hot Reload**: The app checks for new/modified JRXML files every 5 seconds
2. **Caching**: Compiled `.jasper` files are cached; they auto-recompile when source changes
3. **Logging**: Set `logging.level.com.jasperdev=DEBUG` for detailed logs
4. **Testing**: Use the "Test Connection" button before running reports

## License

Internal development tool - not for distribution.
