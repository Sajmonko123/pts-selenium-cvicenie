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
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import sk.testlab.cvicenie02.support.BrowserFactory;
import sk.testlab.cvicenie02.support.TestArtifacts;
import sk.testlab.cvicenie02.support.TestConfig;
import sk.testlab.cvicenie02.support.TextChecks;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Skript vyučujúceho prenesený na skutočný e-shop noxium.pl (Shoptet).
 * Liposomalna melatonina × 1 + Ashwagandha × 1, InPost cez Packeta, platba pri prevzatí.
 * POZOR: test vytvorí SKUTOČNÚ objednávku. Po teste ju treba stornovať v administrácii Shoptetu.
 */
@Tag("gui")
@DisplayName("noxium.pl – objednávka cez Web GUI")
class NoxiumOrderTest {
    private static final String BASE_URL = "https://www.noxium.pl/";

    // Očakávania z cien a pravidiel obchodu (stránka Dostawa i płatności):
    // tovar 79,99 + 64,99 = 144,98 zł; pod 169 zł sa doprava platí: InPost 17,39 zł;
    // platba pri prevzatí 9,99 zł; spolu 172,36 zł.
    private static final String EXPECTED_GOODS = "144,98 zł";
    private static final String EXPECTED_SHIPPING = "17,39 zł";
    private static final String EXPECTED_PAYMENT = "9,99 zł";
    private static final String EXPECTED_TOTAL = "172,36 zł";

    // Kontaktné údaje objednávky. E-mail sa dá zmeniť: mvn test -Dnoxium.email=...
    private static final String CUSTOMER_NAME = "Test Selenium";
    private static final String CUSTOMER_EMAIL = System.getProperty("noxium.email", "info@noxium.pl");
    private static final String CUSTOMER_PHONE = "600000000";
    private static final String CUSTOMER_STREET = "Testowa 1";
    private static final String CUSTOMER_CITY = "Warszawa";
    private static final String CUSTOMER_ZIP = "00-001";

    private WebDriver driver;
    private WebDriverWait wait;
    private TestConfig config;
    private TestArtifacts artifacts;
    private Throwable primaryFailure;

    @BeforeEach
    void openFreshBrowser() {
        config = TestConfig.load();
        artifacts = new TestArtifacts("NoxiumOrderTest.noxiumOrder", config);
        try {
            driver = BrowserFactory.create(config);
            driver.manage().window().setSize(new Dimension(1440, 1000));
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
            wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            artifacts.recordBrowser(driver);
        } catch (RuntimeException | AssertionError error) {
            primaryFailure = error;
            artifacts.failure(driver, "setup-failure", error);
            throw error;
        }
    }

    @Test
    @DisplayName("Melatonina + Ashwagandha, InPost, platba pri prevzatí → 172,36 zł a odoslaná objednávka")
    void noxiumOrder() {
        try {
            // ARRANGE: čistý prehliadač, prázdny košík, cookies odmietnuté.
            step("Otvorenie obchodu a odmietnutie cookies");
            driver.get(BASE_URL);
            click("buttonCookiesReject");
            assertEquals("0,00 zł", TextChecks.normalizeSpaces(visible("headerCartPrice").getText()),
                "Pred testom musí byť košík prázdny.");

            // ACT: pridanie produktov z detailu.
            step("Pridanie produktov do košíka");
            addFromDetail("melatonina/", "79,99 zł");
            addFromDetail("ashwagandha/", "144,98 zł");

            step("Kontrola košíka");
            driver.get(BASE_URL + "koszyk/");
            assertEquals(EXPECTED_GOODS, TextChecks.normalizeSpaces(visible("recapFullPrice").getText()),
                "Nesprávna suma za tovar v košíku.");
            click("buttonNextStep");

            step("Doprava a platba");
            wait.until(ExpectedConditions.urlContains("zamowienie-platnosci-i-wysylki"));
            choosePacketaPoint();
            assertAll("Súhrn v kroku Dostawa i płatność",
                () -> assertTrue(recapContains(EXPECTED_SHIPPING), "V súhrne chýba doprava " + EXPECTED_SHIPPING),
                () -> assertTrue(recapContains(EXPECTED_PAYMENT), "V súhrne chýba platba " + EXPECTED_PAYMENT),
                () -> assertEquals(EXPECTED_TOTAL, TextChecks.normalizeSpaces(visible("recapFullPrice").getText()),
                    "Nesprávna celková suma.")
            );
            artifacts.screenshot(driver, "recap-before-details.png");
            click("buttonNextStep");

            step("Vyplnenie kontaktných údajov");
            wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("zamowienie-platnosci-i-wysylki")));
            typeByName("billFullName", CUSTOMER_NAME);
            typeByName("email", CUSTOMER_EMAIL);
            typeByName("phone", CUSTOMER_PHONE);
            typeByName("billStreet", CUSTOMER_STREET);
            typeByName("billCity", CUSTOMER_CITY);
            typeByName("billZip", CUSTOMER_ZIP);
            assertEquals(EXPECTED_TOTAL, TextChecks.normalizeSpaces(visible("recapFullPrice").getText()),
                "Celková suma sa pred odoslaním zmenila.");

            // ODOSLANIE: iba raz, objednávka je skutočná.
            step("Odoslanie objednávky");
            String checkoutUrl = driver.getCurrentUrl();
            click("buttonNextStep");
            wait.until(ExpectedConditions.not(ExpectedConditions.urlToBe(checkoutUrl)));

            // ASSERT: potvrdenie objednávky.
            step("Overenie potvrdenia objednávky");
            String pageText = driver.findElement(By.tagName("body")).getText();
            artifacts.put("confirmation.url", driver.getCurrentUrl());
            artifacts.screenshot(driver, "order-confirmation.png");
            assertTrue(driver.getCurrentUrl().contains("zamowienie"),
                "Po odoslaní sa mala zobraziť stránka potvrdenia objednávky.");
            assertTrue(pageText.contains(EXPECTED_TOTAL),
                "Potvrdenie objednávky má obsahovať sumu " + EXPECTED_TOTAL + ".");
            artifacts.put("gui.assertions", "PASSED");
            System.out.println("Objednávka odoslaná: " + driver.getCurrentUrl() + " | " + EXPECTED_TOTAL
                + " | treba ju stornovať v administrácii");
        } catch (RuntimeException | AssertionError error) {
            primaryFailure = error;
            artifacts.failure(driver, "failure", error);
            throw error;
        }
    }

    @AfterEach
    void closeBrowser() {
        // Shoptet nedovolí zákazníkovi zrušiť objednávku, storno robí majiteľ v administrácii.
        try {
            if (driver != null) driver.quit();
        } finally {
            if (artifacts != null) {
                artifacts.put("finished.utc", Instant.now().toString());
                artifacts.put("result", primaryFailure != null ? "FAILED" : "PASSED");
            }
        }
    }

    // Detail produktu → "Dodaj do koszyka" → počkať, kým suma v hlavičke ukáže nový stav.
    private void addFromDetail(String path, String expectedHeaderTotal) {
        driver.get(BASE_URL + path);
        WebElement form = visible("formProduct");
        wait.until(ExpectedConditions.elementToBeClickable(form.findElement(testId("buttonAddToCart")))).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(testId("headerCartPrice"), expectedHeaderTotal));
    }

    // Výber výdajného miesta v okne Packeta (iframe s id packeta-widget).
    private void choosePacketaPoint() {
        WebElement link = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("[data-testid='deliveryPoint'] a")));
        link.click();
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("packeta-widget")));
        try {
            // Okno má vlastný cookie banner.
            By deny = By.xpath("//button[normalize-space()='Deny' or normalize-space()='Odrzuć']");
            if (!driver.findElements(deny).isEmpty()) wait.until(ExpectedConditions.elementToBeClickable(deny)).click();

            WebElement search = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("input")));
            search.sendKeys("Warszawa");
            wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//*[contains(normalize-space(), 'Warszawa') and contains(normalize-space(), 'Polska')]")));
            search.sendKeys(Keys.ENTER);

            // Prvý Paczkomat v zozname, potom potvrdenie výberu.
            wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("(//*[normalize-space()='Pl Paczkomaty'])[1]"))).click();
            wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(normalize-space(), 'Wybierz')]"))).click();
        } finally {
            driver.switchTo().defaultContent();
        }
        // Shoptet si uloží zvolené miesto do skrytého poľa.
        wait.until(d -> !d.findElement(By.name("shippingMethodBranch[76]")).getDomProperty("value").isBlank());
    }

    private boolean recapContains(String text) {
        return driver.findElements(testId("recapDeliveryMethod")).stream()
            .anyMatch(e -> TextChecks.normalizeSpaces(e.getText()).contains(text));
    }

    private void step(String message) {
        artifacts.put("step", message);
        System.out.println("[KROK] " + message);
    }

    private static By testId(String id) { return By.cssSelector("[data-testid='" + id + "']"); }
    private WebElement visible(String id) { return wait.until(ExpectedConditions.visibilityOfElementLocated(testId(id))); }
    private void click(String id) { wait.until(ExpectedConditions.elementToBeClickable(testId(id))).click(); }
    private void typeByName(String name, String value) {
        WebElement input = wait.until(ExpectedConditions.elementToBeClickable(By.name(name)));
        input.clear();
        input.sendKeys(value);
    }
}
