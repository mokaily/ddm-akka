package de.ddm.structures;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;

public class TaskArray {

    public static class Task {
        public ColID left;
        public ColID right;
        @Setter
        public boolean started = false;
        @Setter
        public boolean checked = false;
        @Setter
        boolean containsDependency = false;

        public int id = 0;

        public Task(ColID left, ColID right, int id) {
            this.left = left;
            this.right = right;
            this.id = id;
        }

        @Override
        public String toString() {
            return "(" + left + ", " + right + ", " + started + ", " + checked + ", " + containsDependency + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            Task other = (Task) obj;
            return (left.equals(other.left) && right.equals(other.right)) || (left.equals(other.right) && right.equals(other.left));
        }

    }

    public static Task[] generateTaskArray(Map<ColID, TreeSet<String>> map) {
        int n = map.keySet().size();
        int arraySize = n * (n - 1); // n choose 2 * 2, because we need both orders
        Task[] tasks = new Task[arraySize];

        ArrayList<ColID> keys = new ArrayList<>(map.keySet());
        int index = 0;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = 0; j < keys.size(); j++) {
                if (i != j) {
                    tasks[index++] = new Task(keys.get(i), keys.get(j), index);
                }
            }
        }

        return tasks;
    }

}
