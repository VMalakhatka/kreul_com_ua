package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Capture;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MonthlyActivity;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FolioProductEconomicsCalculator {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Result calculate(Capture capture, LocalDate horizonStart,
                            LocalDate asOfDate) {
        Map<String, Map<LocalDate, MonthlyActivity>> activity = new HashMap<>();
        for (MonthlyActivity row : capture.monthlyActivity()) {
            activity.computeIfAbsent(row.sku(), ignored -> new HashMap<>())
                    .put(row.monthStart(), row);
        }

        List<MonthlyMetric> monthly = new ArrayList<>();
        List<CurrentMetric> current = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        LocalDate firstMonth = horizonStart.withDayOfMonth(1);
        LocalDate lastMonth = asOfDate.withDayOfMonth(1);

        for (ProductCard card : capture.products()) {
            BigDecimal openingQty = card.openingQuantityAtHorizon();
            BigDecimal openingValue = card.openingValueAtHorizon();
            List<MonthlyMetric> productMonths = new ArrayList<>();
            LocalDate lastReceipt = null;
            LocalDate lastSale = null;
            LocalDate lastRegularSale = null;
            for (LocalDate month = firstMonth; !month.isAfter(lastMonth);
                 month = month.plusMonths(1)) {
                MonthlyActivity row = activity.getOrDefault(card.sku(), Map.of()).get(month);
                BigDecimal netQty = row == null ? zero() : row.netQuantity();
                BigDecimal netValue = row == null ? zero() : row.netValue();
                BigDecimal closingQty = openingQty.add(netQty);
                BigDecimal closingValue = openingValue.add(netValue);
                BigDecimal averageValue = openingValue.add(closingValue)
                        .divide(TWO, 4, RoundingMode.HALF_UP);
                BigDecimal receiptQty = value(row, MonthlyActivity::receiptQuantity);
                BigDecimal receiptCost = value(row, MonthlyActivity::receiptCost);
                BigDecimal salesQty = value(row, MonthlyActivity::salesQuantity);
                BigDecimal revenue = value(row, MonthlyActivity::salesRevenue);
                BigDecimal cogs = value(row, MonthlyActivity::salesCogs);
                BigDecimal grossProfit = revenue.subtract(cogs);
                BigDecimal regularSalesQty = value(row, MonthlyActivity::regularSalesQuantity);
                BigDecimal regularRevenue = value(row, MonthlyActivity::regularSalesRevenue);
                BigDecimal regularCogs = value(row, MonthlyActivity::regularSalesCogs);
                BigDecimal oneOffSalesQty = value(row, MonthlyActivity::oneOffSalesQuantity);
                BigDecimal oneOffRevenue = value(row, MonthlyActivity::oneOffSalesRevenue);
                BigDecimal oneOffCogs = value(row, MonthlyActivity::oneOffSalesCogs);
                if (row != null && row.lastReceiptDate() != null) {
                    lastReceipt = max(lastReceipt, row.lastReceiptDate());
                }
                if (row != null && row.lastSaleDate() != null) {
                    lastSale = max(lastSale, row.lastSaleDate());
                }
                if (row != null && row.lastRegularSaleDate() != null) {
                    lastRegularSale = max(lastRegularSale, row.lastRegularSaleDate());
                }
                MonthlyMetric metric = new MonthlyMetric(
                        card.sku(), month, openingQty, closingQty,
                        openingValue, closingValue, receiptQty, receiptCost,
                        salesQty, revenue, cogs, grossProfit,
                        regularSalesQty, regularRevenue, regularCogs,
                        regularRevenue.subtract(regularCogs),
                        oneOffSalesQty, oneOffRevenue, oneOffCogs,
                        oneOffRevenue.subtract(oneOffCogs),
                        value(row, MonthlyActivity::returnQuantity),
                        value(row, MonthlyActivity::returnRevenue),
                        averageValue,
                        ratio(cogs, averageValue),
                        ratio(grossProfit, averageValue),
                        percent(salesQty, openingQty.add(receiptQty))
                );
                productMonths.add(metric);
                if (hasEconomicState(metric)) {
                    monthly.add(metric);
                }
                openingQty = closingQty;
                openingValue = closingValue;
            }

            CurrentMetric metric = current(card, productMonths, lastReceipt,
                    lastSale, lastRegularSale, asOfDate);
            current.add(metric);
            alerts.addAll(alerts(metric, card, asOfDate));
        }
        return new Result(List.copyOf(monthly), List.copyOf(current), List.copyOf(alerts));
    }

    private CurrentMetric current(ProductCard card, List<MonthlyMetric> months,
                                  LocalDate lastReceipt, LocalDate lastSale,
                                  LocalDate lastRegularSale,
                                  LocalDate asOfDate) {
        LocalDate currentMonth = asOfDate.withDayOfMonth(1);
        BigDecimal sold30 = sumLastMonths(months, currentMonth, 1, MetricValue.SALES_QUANTITY);
        BigDecimal soldPrevious30 = sumRange(months, currentMonth.minusMonths(1),
                currentMonth.minusMonths(1), MetricValue.SALES_QUANTITY);
        BigDecimal sold90 = sumLastMonths(months, currentMonth, 3, MetricValue.SALES_QUANTITY);
        BigDecimal sold365 = sumLastMonths(months, currentMonth, 12, MetricValue.SALES_QUANTITY);
        BigDecimal sold730 = sumLastMonths(months, currentMonth, 24, MetricValue.SALES_QUANTITY);
        BigDecimal revenue90 = sumLastMonths(months, currentMonth, 3, MetricValue.REVENUE);
        BigDecimal revenue365 = sumLastMonths(months, currentMonth, 12, MetricValue.REVENUE);
        BigDecimal cogs365 = sumLastMonths(months, currentMonth, 12, MetricValue.COGS);
        BigDecimal gross90 = sumLastMonths(months, currentMonth, 3, MetricValue.GROSS_PROFIT);
        BigDecimal gross365 = sumLastMonths(months, currentMonth, 12, MetricValue.GROSS_PROFIT);
        BigDecimal regularSold30 = sumLastMonths(months, currentMonth, 1,
                MetricValue.REGULAR_SALES_QUANTITY);
        BigDecimal regularSoldPrevious30 = sumRange(months, currentMonth.minusMonths(1),
                currentMonth.minusMonths(1), MetricValue.REGULAR_SALES_QUANTITY);
        BigDecimal regularSold90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.REGULAR_SALES_QUANTITY);
        BigDecimal regularSold365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.REGULAR_SALES_QUANTITY);
        BigDecimal regularSold730 = sumLastMonths(months, currentMonth, 24,
                MetricValue.REGULAR_SALES_QUANTITY);
        BigDecimal oneOffSold30 = sumLastMonths(months, currentMonth, 1,
                MetricValue.ONE_OFF_SALES_QUANTITY);
        BigDecimal oneOffSold90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.ONE_OFF_SALES_QUANTITY);
        BigDecimal oneOffSold365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.ONE_OFF_SALES_QUANTITY);
        BigDecimal oneOffSold730 = sumLastMonths(months, currentMonth, 24,
                MetricValue.ONE_OFF_SALES_QUANTITY);
        BigDecimal regularRevenue90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.REGULAR_REVENUE);
        BigDecimal regularRevenue365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.REGULAR_REVENUE);
        BigDecimal oneOffRevenue90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.ONE_OFF_REVENUE);
        BigDecimal oneOffRevenue365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.ONE_OFF_REVENUE);
        BigDecimal regularGross90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.REGULAR_GROSS_PROFIT);
        BigDecimal regularGross365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.REGULAR_GROSS_PROFIT);
        BigDecimal oneOffGross90 = sumLastMonths(months, currentMonth, 3,
                MetricValue.ONE_OFF_GROSS_PROFIT);
        BigDecimal oneOffGross365 = sumLastMonths(months, currentMonth, 12,
                MetricValue.ONE_OFF_GROSS_PROFIT);
        BigDecimal average90 = averageLastMonths(months, currentMonth, 3);
        BigDecimal average365 = averageLastMonths(months, currentMonth, 12);
        BigDecimal available = card.physicalQuantity().subtract(card.reservedQuantity());
        BigDecimal inventoryValue = card.accountingAmount().signum() != 0
                ? card.accountingAmount()
                : card.physicalQuantity().multiply(card.accountingPrice());
        BigDecimal coverage = regularSold90.signum() > 0
                ? available.max(zero()).multiply(new BigDecimal("90"))
                    .divide(regularSold90, 2, RoundingMode.HALF_UP)
                : null;

        String health = health(card, available, regularSold30,
                regularSoldPrevious30, regularSold90, oneOffSold365,
                gross90, coverage, lastRegularSale, asOfDate);
        return new CurrentMetric(
                card.sku(), card.productName(), card.currentSupplier(), card.supplierState(),
                card.physicalQuantity(),
                card.reservedQuantity(), available, card.accountingPrice(),
                inventoryValue, lastReceipt, lastSale, lastRegularSale,
                sold30, sold90, sold365, sold730,
                regularSold30, regularSold90, regularSold365, regularSold730,
                oneOffSold30, oneOffSold90, oneOffSold365, oneOffSold730,
                revenue90, revenue365, gross90, gross365,
                regularRevenue90, regularRevenue365,
                oneOffRevenue90, oneOffRevenue365,
                regularGross90, regularGross365,
                oneOffGross90, oneOffGross365,
                average90, average365, ratio(cogs365, average365),
                ratio(gross365, average365), coverage, health
        );
    }

    private static String health(ProductCard card, BigDecimal available,
                                 BigDecimal sold30, BigDecimal soldPrevious30,
                                 BigDecimal sold90, BigDecimal oneOffSold365,
                                 BigDecimal gross90,
                                 BigDecimal coverage, LocalDate lastSale,
                                 LocalDate asOfDate) {
        if (card.physicalQuantity().signum() < 0) return "DATA_ISSUE";
        if (card.movementCount() > 0 && card.physicalQuantity().signum() != 0
                && card.accountingPrice().signum() == 0) return "DATA_ISSUE";
        if (card.movementCount() == 0) return "NEW";
        if (available.signum() <= 0 && sold90.signum() > 0) return "STOCKOUT";
        if (gross90.signum() < 0) return "LOW_MARGIN";
        if (card.physicalQuantity().signum() > 0 && sold90.signum() == 0
                && oneOffSold365.signum() > 0) return "ONE_OFF_ONLY_STOCK";
        if (card.physicalQuantity().signum() > 0 && lastSale != null
                && ChronoUnit.DAYS.between(lastSale, asOfDate) > 180) return "DEAD_STOCK";
        if (coverage != null && coverage.compareTo(new BigDecimal("180")) > 0)
            return "OVERSTOCK";
        if (soldPrevious30.compareTo(new BigDecimal("5")) >= 0
                && sold30.multiply(TWO).compareTo(soldPrevious30) < 0)
            return "DEMAND_FADING";
        return "HEALTHY";
    }

    private static List<Alert> alerts(CurrentMetric metric, ProductCard card,
                                      LocalDate asOfDate) {
        List<Alert> result = new ArrayList<>();
        switch (metric.healthStatus()) {
            case "DATA_ISSUE" -> result.add(new Alert(card.sku(), "DATA_ISSUE", "ERROR",
                    "Negative stock or non-zero stock with zero accounting price"));
            case "STOCKOUT" -> result.add(new Alert(card.sku(), "STOCKOUT", "HIGH",
                    "Demand exists, but available stock is zero"));
            case "LOW_MARGIN" -> result.add(new Alert(card.sku(), "LOW_MARGIN", "HIGH",
                    "Gross profit for the recent period is negative"));
            case "ONE_OFF_ONLY_STOCK" -> result.add(new Alert(card.sku(),
                    "ONE_OFF_ONLY_STOCK", "MEDIUM",
                    "Stock remains, but recent demand consists only of one-off orders"));
            case "DEAD_STOCK" -> result.add(new Alert(card.sku(), "DEAD_STOCK", "MEDIUM",
                    "Stock exists, but the last sale is older than 180 days"));
            case "OVERSTOCK" -> result.add(new Alert(card.sku(), "OVERSTOCK", "MEDIUM",
                    "Estimated stock coverage exceeds 180 days"));
            case "DEMAND_FADING" -> result.add(new Alert(card.sku(), "DEMAND_FADING", "MEDIUM",
                    "Current calendar-month demand is less than half of the previous month"));
            default -> { }
        }
        return result;
    }

    private static BigDecimal sumLastMonths(List<MonthlyMetric> months,
                                            LocalDate currentMonth,
                                            int count,
                                            MetricValue selector) {
        return sumRange(months, currentMonth.minusMonths(count - 1L),
                currentMonth, selector);
    }

    private static BigDecimal sumRange(List<MonthlyMetric> months,
                                       LocalDate firstMonth,
                                       LocalDate lastMonth,
                                       MetricValue selector) {
        return months.stream()
                .filter(row -> !row.monthStart().isBefore(firstMonth)
                        && !row.monthStart().isAfter(lastMonth))
                .map(selector::value).reduce(zero(), BigDecimal::add);
    }

    private static BigDecimal averageLastMonths(List<MonthlyMetric> months,
                                                LocalDate currentMonth,
                                                int count) {
        LocalDate firstMonth = currentMonth.minusMonths(count - 1L);
        List<MonthlyMetric> selected = months.stream()
                .filter(row -> !row.monthStart().isBefore(firstMonth)
                        && !row.monthStart().isAfter(currentMonth)).toList();
        if (selected.isEmpty()) return zero();
        return selected.stream().map(MonthlyMetric::averageInventoryValue)
                .reduce(zero(), BigDecimal::add)
                .divide(BigDecimal.valueOf(selected.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() <= 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal ratio = ratio(numerator, denominator);
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO;
    }

    private static BigDecimal value(MonthlyActivity row,
                                    java.util.function.Function<MonthlyActivity, BigDecimal> getter) {
        return row == null ? zero() : getter.apply(row);
    }

    private static boolean hasEconomicState(MonthlyMetric row) {
        return row.openingQuantity().signum() != 0
                || row.closingQuantity().signum() != 0
                || row.openingInventoryValue().signum() != 0
                || row.closingInventoryValue().signum() != 0
                || row.receiptQuantity().signum() != 0
                || row.salesQuantity().signum() != 0
                || row.returnQuantity().signum() != 0;
    }

    private enum MetricValue {
        SALES_QUANTITY { BigDecimal value(MonthlyMetric m) { return m.salesQuantity(); } },
        REGULAR_SALES_QUANTITY {
            BigDecimal value(MonthlyMetric m) { return m.regularSalesQuantity(); }
        },
        ONE_OFF_SALES_QUANTITY {
            BigDecimal value(MonthlyMetric m) { return m.oneOffSalesQuantity(); }
        },
        REVENUE { BigDecimal value(MonthlyMetric m) { return m.salesRevenue(); } },
        REGULAR_REVENUE { BigDecimal value(MonthlyMetric m) { return m.regularSalesRevenue(); } },
        ONE_OFF_REVENUE { BigDecimal value(MonthlyMetric m) { return m.oneOffSalesRevenue(); } },
        COGS { BigDecimal value(MonthlyMetric m) { return m.salesCogs(); } },
        GROSS_PROFIT { BigDecimal value(MonthlyMetric m) { return m.grossProfit(); } },
        REGULAR_GROSS_PROFIT {
            BigDecimal value(MonthlyMetric m) { return m.regularGrossProfit(); }
        },
        ONE_OFF_GROSS_PROFIT {
            BigDecimal value(MonthlyMetric m) { return m.oneOffGrossProfit(); }
        };
        abstract BigDecimal value(MonthlyMetric metric);
    }

    public record Result(List<MonthlyMetric> monthly,
                         List<CurrentMetric> current,
                         List<Alert> alerts) { }

    public record MonthlyMetric(
            String sku, LocalDate monthStart,
            BigDecimal openingQuantity, BigDecimal closingQuantity,
            BigDecimal openingInventoryValue, BigDecimal closingInventoryValue,
            BigDecimal receiptQuantity, BigDecimal receiptCost,
            BigDecimal salesQuantity, BigDecimal salesRevenue,
            BigDecimal salesCogs, BigDecimal grossProfit,
            BigDecimal regularSalesQuantity, BigDecimal regularSalesRevenue,
            BigDecimal regularSalesCogs, BigDecimal regularGrossProfit,
            BigDecimal oneOffSalesQuantity, BigDecimal oneOffSalesRevenue,
            BigDecimal oneOffSalesCogs, BigDecimal oneOffGrossProfit,
            BigDecimal returnQuantity, BigDecimal returnRevenue,
            BigDecimal averageInventoryValue, BigDecimal inventoryTurns,
            BigDecimal gmroi, BigDecimal sellThroughPercent) { }

    public record CurrentMetric(
            String sku, String productName, String currentSupplier, String supplierState,
            BigDecimal physicalQuantity,
            BigDecimal reservedQuantity, BigDecimal availableQuantity,
            BigDecimal accountingPrice, BigDecimal inventoryValue,
            LocalDate lastReceiptDate, LocalDate lastSaleDate,
            LocalDate lastRegularSaleDate,
            BigDecimal soldUnits30d, BigDecimal soldUnits90d,
            BigDecimal soldUnits365d, BigDecimal soldUnits730d,
            BigDecimal regularSoldUnits30d, BigDecimal regularSoldUnits90d,
            BigDecimal regularSoldUnits365d, BigDecimal regularSoldUnits730d,
            BigDecimal oneOffSoldUnits30d, BigDecimal oneOffSoldUnits90d,
            BigDecimal oneOffSoldUnits365d, BigDecimal oneOffSoldUnits730d,
            BigDecimal revenue90d, BigDecimal revenue365d,
            BigDecimal grossProfit90d, BigDecimal grossProfit365d,
            BigDecimal regularRevenue90d, BigDecimal regularRevenue365d,
            BigDecimal oneOffRevenue90d, BigDecimal oneOffRevenue365d,
            BigDecimal regularGrossProfit90d, BigDecimal regularGrossProfit365d,
            BigDecimal oneOffGrossProfit90d, BigDecimal oneOffGrossProfit365d,
            BigDecimal averageInventory90d, BigDecimal averageInventory365d,
            BigDecimal inventoryTurns365d, BigDecimal gmroi365d,
            BigDecimal coverageDays, String healthStatus) { }

    public record Alert(String sku, String code, String severity, String details) { }
}
