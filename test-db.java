import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestMySQLConnection {
    public static void main(String[] args) {
        String url = "jdbc:mysql://127.0.0.1:3306/Uniview?useUnicode=true&characterEncoding=utf-8&useSSL=false";
        String username = "sa";
        String password = "11111111";
        
        try {
            Class.forName("com.mysql.jdbc.Driver");
            Connection conn = DriverManager.getConnection(url, username, password);
            
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DmNe");
            
            if (rs.next()) {
                System.out.println("数据库连接成功！DmNe表记录数: " + rs.getInt(1));
            }
            
            rs.close();
            stmt.close();
            conn.close();
            
        } catch (Exception e) {
            System.err.println("数据库连接失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
