package com.example.rf.keywords;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.robotframework.javalib.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@RobotKeywords
public class ExcelKeywords {
  public static final String ROBOT_LIBRARY_SCOPE = "GLOBAL";
  private Workbook wb;
  private Path filePath;

  @RobotKeyword("Open an XLSX file; creates if missing.")
  @ArgumentNames({"path"})
  public void openExcel(String path) {
    try {
      filePath = Path.of(path);
      if (Files.exists(filePath)) try (InputStream in = Files.newInputStream(filePath)) {
        wb = WorkbookFactory.create(in);
      } else {
        wb = new XSSFWorkbook();
      }
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  @RobotKeyword("Read a cell (sheet,row,col) — 1-based indices.")
  @ArgumentNames({"sheet","row","col"})
  public String readCell(String sheet, int row, int col) {
    Sheet sh = wb.getSheet(sheet);
    if (sh==null) throw new IllegalArgumentException("No sheet: "+sheet);
    Row r = sh.getRow(row-1); if (r==null) return "";
    Cell c = r.getCell(col-1); if (c==null) return "";
    return c.getCellType()==CellType.NUMERIC ? String.valueOf(c.getNumericCellValue()) : c.toString();
  }

  @RobotKeyword("Write a cell (sheet,row,col,value).")
  @ArgumentNames({"sheet","row","col","value"})
  public void writeCell(String sheet, int row, int col, String value) {
    Sheet sh = wb.getSheet(sheet); if (sh==null) sh = wb.createSheet(sheet);
    Row r = sh.getRow(row-1); if (r==null) r = sh.createRow(row-1);
    Cell c = r.getCell(col-1); if (c==null) c = r.createCell(col-1);
    c.setCellValue(value);
  }

  @RobotKeyword("Save workbook (overwrites original file).")
  public void saveExcel() {
    try (OutputStream out = Files.newOutputStream(filePath)) { wb.write(out); }
    catch (IOException e) { throw new RuntimeException(e); }
  }

  @RobotKeyword("Close workbook.")
  public void closeExcel() {
    try { wb.close(); } catch (IOException ignored) {}
  }
}
