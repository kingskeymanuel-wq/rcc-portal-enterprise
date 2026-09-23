import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GeneratePassword {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

        String password = "Admin@Rcc2026!";
        String hash = encoder.encode(password);

        System.out.println(hash);
        System.out.println("Verification = " + encoder.matches(password, hash));
    }
}