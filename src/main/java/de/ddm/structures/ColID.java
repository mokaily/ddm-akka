package de.ddm.structures;

import lombok.Getter;

import java.util.Objects;
import java.util.TreeSet;

@Getter
public class ColID {
    int file;
    int column;

    public ColID(int file, int column) {
        this.file = file;
        this.column = column;
    }

    public boolean isSameFile(ColID columnId) {
        return columnId.getFile() == this.file;
    }
    @Override
    public String toString() {
        return "{" + file +
                "," + column +
                '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, column);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ColID colID = (ColID) o;
        return file == colID.file && column == colID.column;
    }


}