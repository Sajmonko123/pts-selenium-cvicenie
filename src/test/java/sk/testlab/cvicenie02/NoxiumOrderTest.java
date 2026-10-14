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
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
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
    private static final String MELATONINA = "Liposomalna melatonina - Noxium";
    private static final String ASHWAGANDHA = "Ashwagandha - Noxium";

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
            assertEquals("0,00 zł", headerCartPrice(),
                "Pred testom musí byť košík prázdny.");

            // ACT: pridanie produktov z detailu.
            step("Pridanie produktov do košíka");
            addFromDetail("melatonina/", "79,99 zł");
            addFromDetail("ashwagandha/", "144,98 zł");

            step("Kontrola košíka");
            driver.get(BASE_URL + "koszyk/");
            assertAll("Produkty a množstvá v košíku",
                () -> assertEquals("1", cartAmount(MELATONINA), "Počet balení melatonínu."),
                () -> assertEquals("1", cartAmount(ASHWAGANDHA), "Počet balení ashwagandhy.")
            );
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
            wait.until(ExpectedConditions.urlContains("dziekujemy"));
            String pageText = TextChecks.normalizeSpaces(driver.findElement(By.tagName("body")).getText());
            // Číslo objednávky ukladáme PRED assertions, aby ostalo v dôkazoch aj pri chybe.
            java.util.regex.Matcher number = java.util.regex.Pattern.compile("Numer\\s+(\\d+)").matcher(pageText);
            String orderNumber = number.find() ? number.group(1) : "";
            artifacts.write("order-id.txt", orderNumber + System.lineSeparator());
            artifacts.put("order.id", orderNumber);
            artifacts.put("confirmation.url", driver.getCurrentUrl());
            artifacts.screenshot(driver, "order-confirmation.png");
            assertAll("Potvrdenie objednávky",
                () -> assertTrue(orderNumber.matches("\\d{10}"), "Potvrdenie musí zobraziť 10-miestne číslo objednávky."),
                () -> assertTrue(pageText.contains("Zamówienie zostało wysłane"), "Chýba správa o odoslaní objednávky."),
                () -> assertTrue(pageText.contains("Kwota do zapłaty " + EXPECTED_TOTAL), "Nesprávna suma na potvrdení."),
                () -> assertTrue(pageText.contains("InPost Paczkomaty i punkty odbioru"), "Chýba zvolená doprava."),
                () -> assertTrue(pageText.contains("Płatność przy odbiorze"), "Chýba zvolená platba."),
                () -> assertTrue(pageText.contains(MELATONINA + " 1 paczki / 79,99 zł"), "Melatonín: názov, množstvo alebo cena."),
                () -> assertTrue(pageText.contains(ASHWAGANDHA + " 1 paczki / 64,99 zł"), "Ashwagandha: názov, množstvo alebo cena.")
            );
            artifacts.put("gui.assertions", "PASSED");
            System.out.println("Objednávka odoslaná: " + orderNumber + " | " + EXPECTED_TOTAL
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
        // Príprava dát: produkt musí byť dostupný.
        String availability = TextChecks.normalizeSpaces(wait.until(ExpectedConditions.presenceOfElementLocated(
            testId("labelAvailability"))).getDomProperty("textContent")).trim();
        assertEquals("Dostępne", availability, "Produkt " + path + " nie je dostupný.");
        WebElement button = form.findElement(testId("buttonAddToCart"));
        clickBelowFold(button);
        wait.until(d -> headerCartPrice().equals(expectedHeaderTotal));
    }

    // Tlačidlo je pod okrajom okna a stránka sa pri bežnom posune vracia hore.
    // Najprv skúsime posun a normálny klik, pri zakrytí klikneme priamo cez JavaScript.
    private void clickBelowFold(WebElement element) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", element);
        wait.until(ExpectedConditions.elementToBeClickable(element));
        try {
            element.click();
        } catch (ElementClickInterceptedException intercepted) {
            js.executeScript("arguments[0].click();", element);
        }
    }

    // Výber výdajného miesta v okne Packeta (iframe s id packeta-widget).
    private void choosePacketaPoint() {
        clickBelowFold(wait.until(ExpectedConditions.presenceOfElementLocated(
            By.cssSelector("[data-testid='deliveryPoint'] a"))));
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.cssSelector("iframe#packeta-widget")));
        try {
            // Okno má vlastnú cookie lištu, ktorá sa ukáže až o chvíľu neskôr.
            By deny = By.xpath("//*[self::button or self::a][normalize-space()='Deny' or normalize-space()='Odrzuć']");
            try {
                new WebDriverWait(driver, Duration.ofSeconds(8)).until(ExpectedConditions.elementToBeClickable(deny)).click();
                wait.until(ExpectedConditions.invisibilityOfElementLocated(deny));
            } catch (TimeoutException noBanner) {
                // Lišta sa neukázala, pokračujeme.
            }

            // Prvý Paczkomat v zozname najbližších miest, potom potvrdenie výberu.
            wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("(//*[normalize-space()='Pl Paczkomaty'])[1]"))).click();
            artifacts.write("packeta-after-point.html", driver.getPageSource());
            wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(translate(normalize-space(), 'WYBIERZ', 'wybierz'), 'wybierz')]"))).click();
        } finally {
            driver.switchTo().defaultContent();
        }
        // Počkáme, kým sa okno Packeta zatvorí.
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("div#packeta-widget")));
        // Shoptet si uloží zvolené miesto do skrytého poľa.
        wait.until(d -> !d.findElement(By.name("shippingMethodBranch[76]")).getDomProperty("value").isBlank());
    }

    // Suma v hlavičke je na počítači skrytá (vidno len ikonu košíka), preto čítame textContent.
    private String headerCartPrice() {
        WebElement price = wait.until(ExpectedConditions.presenceOfElementLocated(testId("headerCartPrice")));
        return TextChecks.normalizeSpaces(price.getDomProperty("textContent")).trim();
    }

    // Množstvo v riadku košíka s daným názvom produktu.
    private String cartAmount(String productName) {
        return wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath(
            "//tr[.//*[@data-testid='cartProductName' and normalize-space()='" + productName + "']]"
                + "//input[@data-testid='cartAmount']"))).getDomProperty("value");
    }

    private boolean recapContains(String text) {
        return driver.findElements(testId("recapDeliveryMethod")).stream()
            .anyMatch(e -> TextChecks.normalizeSpaces(e.getDomProperty("textContent")).contains(text));
    }

    private void step(String message) {
        artifacts.put("step", message);
        System.out.println("[KROK] " + message);
    }

    private static By testId(String id) { return By.cssSelector("[data-testid='" + id + "']"); }
    private WebElement visible(String id) { return wait.until(ExpectedConditions.visibilityOfElementLocated(testId(id))); }
    private void click(String id) {
        clickBelowFold(wait.until(ExpectedConditions.presenceOfElementLocated(testId(id))));
    }

    // Rovnaký názov môže mať aj skryté pole (napr. prihlásenie v hlavičke), berieme viditeľné.
    private void typeByName(String name, String value) {
        WebElement input = wait.until(d -> d.findElements(By.name(name)).stream()
            .filter(WebElement::isDisplayed).findFirst().orElse(null));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", input);
        input.clear();
        input.sendKeys(value);
    }
}
