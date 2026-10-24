package cn.ac.ios.justin.samples;

import cn.ac.ios.justin.annotations.ArrayPara;
import cn.ac.ios.justin.annotations.Element;
import cn.ac.ios.justin.annotations.Field;
import cn.ac.ios.justin.annotations.Max;
import cn.ac.ios.justin.annotations.Min;
import cn.ac.ios.justin.annotations.NonNull;
import cn.ac.ios.justin.annotations.Pattern;
import cn.ac.ios.justin.annotations.SubClass;

/** JNN-style annotation basis: structural composition, array/length binding, and return contracts. */
public final class Enrollment {

    public static class Person {
        private String name;
        @Min(0) @Max(150) private int age;

        public Person() {}
        public Person(String name, int age) { this.name = name; this.age = age; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    public static class Student extends Person {
        private String studentId;
        public Student() {}
        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }
    }

    /** Ticket discount from the JNN motivating example: the annotation gives the realistic domain. */
    @Min(0) @Max(200)
    public static int discount(@Min(0) @Max(150) int age, @Min(0) @Max(200) int price) {
        if (age <= 12 || age >= 60) return price / 2;
        return price;
    }

    /** The second element of ps must be a Student aged 5..22 (JNN Listing 3). */
    public static String enroll(@NonNull @Element(1) @SubClass(Student.class) @Field("age") @Min(5) @Max(22) Person[] ps) {
        if (ps.length < 2) throw new IllegalArgumentException("need two people");
        Person p = ps[1];
        if (!(p instanceof Student)) throw new IllegalArgumentException("second must be a student");
        if (p.getAge() < 5 || p.getAge() > 22) throw new IllegalArgumentException("student age");
        return "S" + p.getAge();
    }

    /**
     * A field constraint on an object parameter (the class's own @Min/@Max on age applies as well);
     * the return value carries a regex contract.
     */
    @Pattern("[A-Z]{2}-[0-9]{3}")
    public static String code(@Field("name") @Pattern("[A-Z]{2}") Person p) {
        if (p.getName() == null || !p.getName().matches("[A-Z]{2}")) throw new IllegalArgumentException("name");
        if (p.getAge() < 0 || p.getAge() > 150) throw new IllegalArgumentException("age");
        return p.getName() + "-" + String.format("%03d", p.getAge());
    }

    /** len is bound to the array length (JNN @ArrayPara). */
    public static int sum(int[] values, @ArrayPara(paraIndex = 0) int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += values[i];
        return s;
    }

    /** A return contract the implementation violates for some inputs: the semantic oracle should flag it. */
    @Min(1)
    public static int halve(@Min(0) @Max(100) int n) {
        return n / 2;
    }
}
