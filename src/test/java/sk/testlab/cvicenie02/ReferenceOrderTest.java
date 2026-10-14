package sk.testlab.cvicenie02;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import sk.testlab.cvicenie02.support.BrowserFactory;
import sk.testlab.cvicenie02.support.TestArtifacts;
import sk.testlab.cvicenie02.support.TestConfig;
import sk.testlab.cvicenie02.support.TextChecks;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hotové riešenie praktickej časti prezentácie 02_Selenium_Java_Web_GUI.pptx.
 * Jeden test, jedna používateľská cesta, explicitné assertions.
 * Žiadne priame API požiadavky, SQL dotazy ani Page Object Model.
 */
@Tag("gui")
@DisplayName("Cvičenie 02 – referenčná objednávka cez Web GUI")
class ReferenceOrderTest {
    // Očakávania pochádzajú z ACCEPTANCE.md, NIE z práve vypočítanej cenovej ponuky.
    private static final String EXPECTED_TOTAL_CENTS = "8563";
    private static final String EXPECTED_VISIBLE_TOTAL = "85,63 €";
    private static final String EXPECTED_STATUS = "created";

    private WebDriver driver;
    private WebDriverWait wait;
    private TestConfig config;
    private TestArtifacts artifacts;
    private String createdOrderId = "";
    private boolean orderSubmitAttempted;
    private Throwable primaryFailure;

    @BeforeEach
    void openFreshBrowser() {
        config = TestConfig.load();
        artifacts = new TestArtifacts("ReferenceOrderTest.referenceOrder", config);
        try {
            driver = BrowserFactory.create(config);
            driver.manage().window().setSize(new Dimension(1440, 1000));
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
            driver.manage().timeouts().pageLoadTimeout(config.timeout.multipliedBy(2));
            wait = new WebDriverWait(driver, config.timeout);
            artifacts.recordBrowser(driver);
        } catch (RuntimeException | AssertionError error) {
            primaryFailure = error;
            artifacts.failure(driver, "setup-failure", error);
            throw error;
        }
    }

    @Test
    @DisplayName("KB-001 × 2 + MS-001 × 1, STUDENT10, kuriér → 85,63 € a created")
    void referenceOrder() {
        try {
            // ARRANGE: samostatný účet od vyučujúceho + nový profil prehliadača.
            step("Prihlásenie do izolovaného pracovného priestoru");
            driver.get(config.baseUrl);
            click("nav-login");
            type("login-email", config.email);
            type("login-password", config.password);
            click("login-submit");
            visible("current-user");
            visible("catalog-ready");

            // Nový profil má čistý localStorage. Nekonáme nad košíkom iného behu.
            click("nav-cart");
            assertTrue(visible("cart-empty").isDisplayed(), "Pred testom musí byť košík prázdny.");
            click("nav-catalog");
            visible("catalog-ready");
            assertTrue(Integer.parseInt(visible("stock-KB-001").getDomAttribute("data-stock")) >= 2,
                "Príprava dát: potrebujeme aspoň 2 klávesnice KB-001.");
            assertTrue(Integer.parseInt(visible("stock-MS-001").getDomAttribute("data-stock")) >= 1,
                "Príprava dát: potrebujeme aspoň 1 myš MS-001.");

            // ACT: používateľské akcie prebiehajú výhradne cez Selenium.
            step("Výber produktov a doručenia");
            click("add-to-cart-KB-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "1"));
            click("add-to-cart-KB-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "2"));
            click("add-to-cart-MS-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "3"));
            click("nav-cart");
            assertEquals("2", visible("quantity-KB-001").getDomProperty("value"));
            assertEquals("1", visible("quantity-MS-001").getDomProperty("value"));
            new Select(visible("delivery-method")).selectByValue("courier");

            step("Uplatnenie kupónu STUDENT10");
            WebElement previousQuote = visible("quote-total");
            type("coupon-input", "STUDENT10");
            click("coupon-apply");
            // Vue odstráni starý súhrn a po odpovedi servera vykreslí nový.
            // Nečakáme na požadovanú cenu: chybné číslo má odhaliť assertion, nie timeout.
            wait.until(ExpectedConditions.stalenessOf(previousQuote));
            visible("quote-total");
            click("proceed-checkout");

            step("Vyplnenie simulovaných kontaktných údajov");
            visible("checkout-form");
            type("customer-name", "Študent Testovací");
            type("customer-email", "objednavka@example.test");
            type("customer-address", "Testovacia 12");
            type("customer-city", "Žilina");
            type("customer-postal-code", "010 01");
            type("order-note", "Cvičenie 02; beh " + artifacts.runId);
            click("accept-terms");

            step("Vytvorenie objednávky");
            WebElement placeOrder = wait.until(ExpectedConditions.elementToBeClickable(testId("place-order")));
            orderSubmitAttempted = true;
            placeOrder.click(); // IBA RAZ. Pri neznámom výsledku test objednávku naslepo neopakuje.

            // UUID ukladáme PRED assertions; pri nesprávnej cene zostane použiteľný dôkaz.
            createdOrderId = visible("order-id").getText().trim();
            saveOrderId();
            WebElement total = visible("order-total");
            String actualCents = total.getDomAttribute("data-cents");
            String actualVisible = TextChecks.normalizeSpaces(total.getText());
            String actualStatus = visible("order-status").getDomAttribute("data-status");
            artifacts.put("observed.total.cents", actualCents);
            artifacts.put("observed.total.text", actualVisible);
            artifacts.put("observed.status.before.cleanup", actualStatus);

            // ASSERT: porovnávame s požiadavkou, nie iba dve hodnoty z tej istej aplikácie.
            step("Explicitné overenie výsledku objednávky");
            assertAll("Referenčná objednávka podľa ACCEPTANCE.md",
                () -> assertTrue(TextChecks.isCanonicalUuid(createdOrderId), "GUI musí zobraziť platné UUID objednávky."),
                () -> assertEquals(EXPECTED_TOTAL_CENTS, actualCents, "Nesprávna suma v centoch."),
                () -> assertEquals(EXPECTED_VISIBLE_TOTAL, actualVisible, "Nesprávna viditeľná suma alebo mena."),
                () -> assertEquals(EXPECTED_STATUS, actualStatus, "Nová objednávka musí mať stav created."),
                () -> assertEquals(createdOrderId, TextChecks.uuidFromOrderUrl(driver.getCurrentUrl()),
                    "UUID v adrese a v detaile musia patriť tej istej objednávke."),
                () -> assertEquals("2", visible("order-quantity-KB-001").getText().trim(), "Počet klávesníc."),
                () -> assertEquals("1", visible("order-quantity-MS-001").getText().trim(), "Počet myší.")
            );
            artifacts.screenshot(driver, "passed-before-cleanup.png");
            artifacts.put("gui.assertions", "PASSED");
            System.out.println("Overená objednávka: " + createdOrderId + " | 8563 centov | created");
        } catch (RuntimeException | AssertionError error) {
            primaryFailure = error;
            // Najprv dôkaz, až potom @AfterEach a zatvorenie prehliadača.
            artifacts.failure(driver, "failure", error);
            throw error; // Zlyhanie sa nesmie zmeniť na falošne zelený test.
        }
    }

    @Test
    @DisplayName("CAM-001 × 1 + USB-001 × 1 + MS-001 × 1, STUDENT10, osobný odber → 82,26 € a created")
    void pickupOrder() {
        // Očakávania vypočítané ručne podľa ACCEPTANCE.md:
        // medzisúčet 5900 + 1250 + 1990 = 9140; zľava 10 % = 914; osobný odber = 0;
        // spolu 8226.
        final String expectedSubtotalCents = "9140";
        final String expectedDiscountCents = "914";
        final String expectedShippingCents = "0";
        final String expectedTotalCents = "8226";
        final String expectedVisibleTotal = "82,26 €";
        try {
            // ARRANGE: prihlásenie, prázdny košík a dostatočný sklad.
            step("Prihlásenie do izolovaného pracovného priestoru");
            driver.get(config.baseUrl);
            click("nav-login");
            type("login-email", config.email);
            type("login-password", config.password);
            click("login-submit");
            visible("current-user");
            visible("catalog-ready");

            click("nav-cart");
            assertTrue(visible("cart-empty").isDisplayed(), "Pred testom musí byť košík prázdny.");
            click("nav-catalog");
            visible("catalog-ready");
            assertTrue(Integer.parseInt(visible("stock-CAM-001").getDomAttribute("data-stock")) >= 1,
                    "Príprava dát: potrebujeme aspoň 1 webovú kameru CAM-001.");
            assertTrue(Integer.parseInt(visible("stock-USB-001").getDomAttribute("data-stock")) >= 1,
                    "Príprava dát: potrebujeme aspoň 1 USB-C kábel USB-001.");
            assertTrue(Integer.parseInt(visible("stock-MS-001").getDomAttribute("data-stock")) >= 1,
                    "Príprava dát: potrebujeme aspoň 1 myš MS-001.");

            // ACT: výber produktov, osobný odber a kupón.
            step("Výber produktov a osobného odberu");
            click("add-to-cart-CAM-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "1"));
            click("add-to-cart-USB-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "2"));
            click("add-to-cart-MS-001");
            wait.until(ExpectedConditions.textToBe(testId("cart-count"), "3"));
            click("nav-cart");
            assertEquals("1", visible("quantity-CAM-001").getDomProperty("value"));
            assertEquals("1", visible("quantity-USB-001").getDomProperty("value"));
            assertEquals("1", visible("quantity-MS-001").getDomProperty("value"));

            // Zmena doručenia spustí nový prepočet; počkáme, kým sa starý súhrn nahradí
            // novým.
            WebElement courierQuote = visible("quote-total");
            new Select(visible("delivery-method")).selectByValue("pickup");
            wait.until(ExpectedConditions.stalenessOf(courierQuote));

            step("Uplatnenie kupónu STUDENT10");
            WebElement pickupQuote = visible("quote-total");
            type("coupon-input", "STUDENT10");
            click("coupon-apply");
            wait.until(ExpectedConditions.stalenessOf(pickupQuote));
            visible("quote-total");
            click("proceed-checkout");

            // Pri osobnom odbere formulár adresu nevyžaduje ani nezobrazuje.
            step("Vyplnenie kontaktných údajov bez adresy");
            visible("checkout-form");
            assertTrue(driver.findElements(testId("customer-address")).isEmpty(),
                    "Pri osobnom odbere sa pole adresy nemá zobraziť.");
            type("customer-name", "Študent Testovací");
            type("customer-email", "objednavka@example.test");
            type("order-note", "Cvičenie 02 – osobný odber; beh " + artifacts.runId);
            click("accept-terms");

            step("Vytvorenie objednávky");
            WebElement placeOrder = wait.until(ExpectedConditions.elementToBeClickable(testId("place-order")));
            orderSubmitAttempted = true;
            placeOrder.click(); // IBA RAZ.

            createdOrderId = visible("order-id").getText().trim();
            saveOrderId();
            WebElement total = visible("order-total");
            String actualCents = total.getDomAttribute("data-cents");
            String actualVisible = TextChecks.normalizeSpaces(total.getText());
            String actualStatus = visible("order-status").getDomAttribute("data-status");
            artifacts.put("observed.total.cents", actualCents);
            artifacts.put("observed.total.text", actualVisible);
            artifacts.put("observed.status.before.cleanup", actualStatus);

            // ASSERT: porovnanie so špecifikáciou.
            step("Explicitné overenie výsledku objednávky");
            assertAll("Objednávka s osobným odberom podľa ACCEPTANCE.md",
                    () -> assertTrue(TextChecks.isCanonicalUuid(createdOrderId),
                            "GUI musí zobraziť platné UUID objednávky."),
                    () -> assertEquals(expectedSubtotalCents, visible("order-subtotal").getDomAttribute("data-cents"),
                            "Nesprávny medzisúčet."),
                    () -> assertEquals(expectedDiscountCents, visible("order-discount").getDomAttribute("data-cents"),
                            "Nesprávna zľava."),
                    () -> assertEquals(expectedShippingCents, visible("order-shipping").getDomAttribute("data-cents"),
                            "Osobný odber musí byť zadarmo."),
                    () -> assertEquals(expectedTotalCents, actualCents, "Nesprávna suma v centoch."),
                    () -> assertEquals(expectedVisibleTotal, actualVisible, "Nesprávna viditeľná suma alebo mena."),
                    () -> assertEquals(EXPECTED_STATUS, actualStatus, "Nová objednávka musí mať stav created."),
                    () -> assertEquals("1", visible("order-quantity-CAM-001").getText().trim(),
                            "Počet webových kamier."),
                    () -> assertEquals("1", visible("order-quantity-USB-001").getText().trim(), "Počet USB-C káblov."),
                    () -> assertEquals("1", visible("order-quantity-MS-001").getText().trim(),
                            "Počet bezdrôtových myší."));
            artifacts.screenshot(driver, "passed-before-cleanup.png");
            artifacts.put("gui.assertions", "PASSED");
            System.out.println("Overená objednávka: " + createdOrderId + " | 8226 centov | created");
        } catch (RuntimeException | AssertionError error) {
            primaryFailure = error;
            artifacts.failure(driver, "failure", error);
            throw error;
        }
    }

    @AfterEach
    void cleanUpAndCloseBrowser() {
        Throwable teardownFailure = null;
        try {
            if (driver != null) cleanUpOwnOrder();
        } catch (RuntimeException | AssertionError error) {
            teardownFailure = error;
            if (artifacts != null) artifacts.failure(driver, "cleanup-failure", error);
        } finally {
            if (driver != null) {
                try { driver.quit(); }
                catch (RuntimeException error) {
                    if (teardownFailure == null) teardownFailure = error;
                    else teardownFailure.addSuppressed(error);
                }
            }
        }
        if (artifacts != null) {
            try {
                artifacts.put("finished.utc", Instant.now().toString());
                artifacts.put("result", primaryFailure != null ? "FAILED"
                    : teardownFailure != null ? "TEARDOWN_FAILED" : "PASSED");
            } catch (RuntimeException error) {
                if (teardownFailure == null) teardownFailure = error;
                else teardownFailure.addSuppressed(error);
            }
        }
        if (teardownFailure != null) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(teardownFailure);
                System.err.println("Zlyhalo aj upratanie; pôvodná chyba zostáva hlavnou príčinou: " + teardownFailure);
            } else {
                throw new AssertionError("GUI assertions prešli, ale upratanie alebo zatvorenie prehliadača zlyhalo.", teardownFailure);
            }
        }
    }

    private void cleanUpOwnOrder() {
        if (!orderSubmitAttempted) {
            artifacts.put("cleanup.status", "NOT_NEEDED_NO_SUBMIT");
            return;
        }
        if (!TextChecks.isCanonicalUuid(createdOrderId)) {
            // Len URL, na ktorú sme prišli po našom jedinom odoslaní. Žiadne prehľadávanie histórie.
            String recovered = TextChecks.uuidFromOrderUrl(driver.getCurrentUrl());
            if (TextChecks.isCanonicalUuid(recovered)) { createdOrderId = recovered; saveOrderId(); }
        }
        if (!config.cleanupOrder) {
            artifacts.put("cleanup.status", "DISABLED_ORDER_RETAINED");
            return;
        }
        if (!TextChecks.isCanonicalUuid(createdOrderId)) {
            artifacts.put("cleanup.status", "UNKNOWN_ORDER_ID_MANUAL_REVIEW_REQUIRED");
            throw new IllegalStateException("Odoslanie prebehlo, ale UUID nie je známe."
                + " Skontrolujte vlastný pracovný priestor; test nesmie rušiť cudzie objednávky.");
        }
        String detailUrl = config.baseUrl + "#/orders/" + createdOrderId;
        if (!createdOrderId.equals(TextChecks.uuidFromOrderUrl(driver.getCurrentUrl()))) driver.get(detailUrl);
        assertEquals(createdOrderId, visible("order-id").getText().trim(), "Upratovanie smie pracovať iba s vlastným UUID.");
        String status = visible("order-status").getDomAttribute("data-status");
        if ("created".equals(status)) {
            click("cancel-order");
            click("confirm-cancel");
            wait.until(ExpectedConditions.attributeToBe(testId("order-status"), "data-status", "cancelled"));
        } else if (!"cancelled".equals(status)) {
            throw new IllegalStateException("Objednávku nemožno bezpečne upratať; neočakávaný stav: " + status);
        }
        artifacts.put("cleanup.status", "CANCELLED_OWN_ORDER");
        artifacts.put("cleanup.order.id", createdOrderId);
    }

    private void saveOrderId() {
        artifacts.write("order-id.txt", createdOrderId + System.lineSeparator());
        artifacts.put("order.id", createdOrderId);
    }

    private void step(String message) {
        artifacts.put("step", message);
        System.out.println("[KROK] " + message);
    }

    // Len tri malé Selenium operácie. Page Object Model patrí do ďalšieho cvičenia.
    private static By testId(String id) { return By.cssSelector("[data-testid='" + id + "']"); }
    private WebElement visible(String id) { return wait.until(ExpectedConditions.visibilityOfElementLocated(testId(id))); }
    private void click(String id) { wait.until(ExpectedConditions.elementToBeClickable(testId(id))).click(); }
    private void type(String id, String value) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(testId(id)));
        input.clear();
        input.sendKeys(value);
    }
}
